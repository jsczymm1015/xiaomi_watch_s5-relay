package local.watchdesk.relay;

import java.io.*;
import java.util.Arrays;
import java.util.concurrent.*;

/** SAR v2 over an RFCOMM byte stream. Reader independently handles ACK/NAK. */
public final class SarLink implements Channel {
    private final InputStream input;
    private final OutputStream output;
    private final Closeable socket;
    private final Object state=new Object(), writeLock=new Object();
    private final BlockingQueue<byte[]> messages=new ArrayBlockingQueue<>(128);
    private volatile IOException failure;
    private volatile boolean closed;
    private boolean ready, acknowledged, retry;
    private int txSequence, rxSequence, pending=-1, mps=64512, timeout=10000;
    public SarLink(InputStream input,OutputStream output,Closeable socket) {
        this.input=input;this.output=output;this.socket=socket;
        Thread reader=new Thread(this::readLoop,"watch-sar-reader");reader.setDaemon(true);reader.start();
    }
    public void start() throws IOException,InterruptedException {
        raw(new byte[]{(byte)0xba,(byte)0xdc,(byte)0xfe,0,(byte)0xc0,3,0,0,1,0,(byte)0xef});
        byte[] command={1,1,3,0,1,0,0,2,2,0,0,(byte)0xfc,3,2,0,1,0,4,2,0,0x10,0x27};
        for(int attempt=0;attempt<3;attempt++) {
            raw(frame(2,0,command));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            synchronized(state){while(!ready){check();long left=until-System.nanoTime();if(left<=0)break;TimeUnit.NANOSECONDS.timedWait(state,left);}if(ready)return;}
        }
        throw new IOException("手表没有响应 SAR 握手，请检查连接模式");
    }
    @Override public int maxPayload(){return mps;}
    @Override public synchronized void send(byte[] payload) throws IOException,InterruptedException {
        if(payload.length>mps)throw new IOException("数据片超过手表协商上限");
        int seq=txSequence;byte[] packet=frame(3,seq,payload);
        synchronized(state){check();if(!ready)throw new IOException("SAR 未就绪");pending=seq;acknowledged=false;retry=false;}
        try {
            for(int attempt=0;attempt<5;attempt++) {
                synchronized(state){check();retry=false;}
                raw(packet);long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
                synchronized(state){
                    while(!acknowledged&&!retry){check();long left=until-System.nanoTime();if(left<=0)break;TimeUnit.NANOSECONDS.timedWait(state,left);}
                    check();if(acknowledged){txSequence=(seq+1)&255;return;}
                }
            }
            throw new IOException("蓝牙数据片未获确认，已停止传输；请重新连接后核对表盘列表");
        } finally { synchronized(state){pending=-1;} }
    }
    @Override public byte[] receive(long timeoutMs) throws IOException,InterruptedException {
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while(true){check();long left=until-System.nanoTime();if(left<=0)throw new IOException("等待手表响应超时");
            byte[] p=messages.poll(Math.min(250,Math.max(1,TimeUnit.NANOSECONDS.toMillis(left))),TimeUnit.MILLISECONDS);if(p!=null)return p;}
    }
    private void raw(byte[] bytes) throws IOException { synchronized(writeLock){check();output.write(bytes);output.flush();} }
    private void check() throws IOException { if(failure!=null)throw failure;if(closed)throw new IOException("连接已关闭"); }
    private void readLoop() {
        try {
            while(!closed) {
                int a=input.read();if(a<0)throw new EOFException("手表已断开");if(a!=0xa5)continue;
                int b=input.read();if(b<0)throw new EOFException("手表已断开");if(b!=0xa5)continue;
                byte[] h=new byte[6];fully(input,h);int type=h[0]&15,seq=h[1]&255,len=FaceFile.u16(h,2);
                byte[] p=new byte[len];fully(input,p);
                if(type>3)continue;
                if(crc16(p)!=FaceFile.u16(h,4)){raw(frame(0,rxSequence,new byte[0]));continue;}
                if(type==2){command(p);continue;}
                if(type<=1){synchronized(state){if(seq==pending){if(type==1)acknowledged=true;else retry=true;state.notifyAll();}}continue;}
                if(!ready)throw new IOException("手表在握手完成前发送数据");
                // Network and multimodal channels are outside the reliable sequence space.
                if(p.length>0&&(p[0]==7||p[0]==10))continue;
                boolean fast=(h[0]&16)!=0;
                if(!fast){
                    if(seq!=rxSequence){int ahead=(seq-rxSequence)&255;raw(frame(ahead<128?0:1,ahead<128?rxSequence:seq,new byte[0]));continue;}
                    raw(frame(1,seq,new byte[0]));rxSequence=(rxSequence+1)&255;
                }
                if(p.length<2)throw new IOException("手表返回截断的 L2 数据");
                if(p[0]==1&&!messages.offer(p))throw new IOException("手表响应队列溢出");
            }
        } catch(IOException e){if(!closed)failure=e;}
        finally { synchronized(state){state.notifyAll();} }
    }
    private void command(byte[] p) throws IOException {
        if(p.length==0)return;
        if(p[0]==3||p[0]==4)throw new IOException("手表终止 SAR 会话");
        if(p[0]!=2)return;
        int negotiated=64512, wait=10000;
        for(int at=1;at<p.length;){if(p.length-at<3)throw new IOException("握手 TLV 截断");int key=p[at]&255,n=FaceFile.u16(p,at+1);at+=3;
            if(n>p.length-at)throw new IOException("握手 TLV 越界");
            if(key==2){if(n!=2)throw new IOException("MPS 长度错误");negotiated=FaceFile.u16(p,at);}
            if(key==4){if(n!=2)throw new IOException("超时参数长度错误");wait=FaceFile.u16(p,at);}
            at+=n;
        }
        if(negotiated<64)throw new IOException("手表协商的数据片大小过小");
        synchronized(state){mps=Math.min(64512,negotiated);timeout=Math.max(1000,Math.min(10000,wait));ready=true;state.notifyAll();}
    }
    public static byte[] frame(int type,int seq,byte[] p) {
        if(p.length>65535)throw new IllegalArgumentException("SAR frame too large");
        byte[] out=new byte[8+p.length];out[0]=(byte)0xa5;out[1]=(byte)0xa5;out[2]=(byte)type;out[3]=(byte)seq;
        FaceFile.put16(out,4,p.length);FaceFile.put16(out,6,crc16(p));System.arraycopy(p,0,out,8,p.length);return out;
    }
    public static int crc16(byte[] p) {int c=0;for(byte b:p){c^=b&255;for(int i=0;i<8;i++)c=(c&1)!=0?(c>>>1)^0xa001:c>>>1;}return c;}
    private static void fully(InputStream in,byte[] b)throws IOException {int at=0,n;while(at<b.length){n=in.read(b,at,b.length-at);if(n<0)throw new EOFException("蓝牙包截断");at+=n;}}
    @Override public void close() throws IOException {closed=true;try{socket.close();}finally{synchronized(state){state.notifyAll();}}}
}
