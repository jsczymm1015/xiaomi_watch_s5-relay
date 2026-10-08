package local.watchdesk.relay;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class WatchClient implements AutoCloseable {
    public interface Progress { void update(String stage,int percent); }
    private final Channel channel;
    private final Progress progress;
    private final List<Proto.Node> inbox=new ArrayList<>();
    private byte[] enc,dec;
    private boolean authenticated,targetChecked;
    public boolean installed;
    public WatchClient(Channel channel,Progress progress){this.channel=channel;this.progress=progress;}
    public void authenticate(byte[] authKey) throws Exception {
        byte[] nonce=new byte[16];new SecureRandom().nextBytes(nonce);
        progress.update("验证手表身份",0);
        send(Proto.wear(1,26,3,new Proto().message(30,new Proto().bytes(1,nonce))),false);
        Proto.Node response=await(1,26,15000).node(3);
        if(response.has(3))throw new IOException("手表拒绝认证，请检查 AuthKey");
        Proto.Node verify=response.node(31);byte[] watch=verify.bytes(1),sign=verify.bytes(2);
        byte[] keys=Crypto.derive(authKey,nonce,watch);
        dec=Arrays.copyOfRange(keys,0,16);enc=Arrays.copyOfRange(keys,16,32);
        try {
            if(!MessageDigest.isEqual(sign,Crypto.hmac(dec,Crypto.concat(watch,nonce))))throw new IOException("AuthKey 与手表不匹配");
            byte[] ccmNonce=new byte[12];System.arraycopy(keys,36,ccmNonce,0,4);
            Proto companion=new Proto().number(1,0).text(3,"Watch Desk Relay").number(4,0xffffffffL);
            Proto confirm=new Proto().bytes(1,Crypto.hmac(enc,Crypto.concat(nonce,watch))).bytes(2,Crypto.ccm(enc,ccmNonce,companion.encode()));
            send(Proto.wear(1,27,3,new Proto().message(32,confirm)),false);
            Proto.Node answer=await(1,27,15000).node(3);
            if(answer.has(3)||answer.node(33).number(1)!=1)throw new IOException("手表未确认认证");
            authenticated=true;
        } finally {Arrays.fill(keys,(byte)0);Arrays.fill(nonce,(byte)0);}
        progress.update("认证成功，读取设备型号",0);
        send(Proto.wear(2,2,0,null),true);
        Proto.Node device=await(2,2,15000).node(4).node(3);
        String identity=TargetProfile.verify(device);
        targetChecked=true;progress.update("已连接 "+identity,0);
    }
    public void install(FaceFile face) throws Exception {
        if(!authenticated||!targetChecked)throw new IOException("尚未完成 Q63 设备认证");
        installed=false;
        progress.update("请求手表准备安装",0);
        Proto info=new Proto().text(1,face.id).number(2,face.size).number(3,65536);
        send(Proto.wear(4,4,6,new Proto().message(6,info)),true);
        Proto.Node wf=await(4,4,20000).node(6);
        long status;
        if(wf.has(9)){Proto.Node reply=wf.node(9);if(!face.id.equals(reply.text(1)))throw new IOException("表盘准备响应 ID 不匹配");status=reply.number(2);}
        else status=wf.number(5);
        requireReady(status);
        Proto request=new Proto().number(1,16).bytes(2,face.md5).number(3,face.size);
        send(Proto.wear(22,0,24,new Proto().message(1,request)),true);
        Proto.Node mass=await(22,0,20000).node(24).node(2);
        if(!MessageDigest.isEqual(face.md5,mass.bytes(1)))throw new IOException("续传文件摘要不匹配");
        requireReady(mass.number(2));
        if(mass.has(3)&&mass.number(3)!=0)throw new IOException("手表要求暂未支持的压缩方式");
        long resume=mass.has(4)?mass.number(4):0, slice=mass.number(5);
        if(resume<0||resume>face.size||slice<=6||slice>65535)throw new IOException("手表返回无效的续传参数");
        int fragment=(int)Math.min(slice,channel.maxPayload())-6;
        if(fragment<=0)throw new IOException("协商数据片大小错误");
        try(FaceFile.MassStream stream=face.mass((int)resume)) {
            int total=(stream.length+fragment-1)/fragment;
            if(total>65535)throw new IOException("数据片数量超过协议上限");
            int sent=0;
            for(int part=1;part<=total;part++) {
                int n=Math.min(fragment,stream.length-sent);byte[] packet=new byte[n+6];packet[0]=2;packet[1]=1;
                FaceFile.put16(packet,2,total);FaceFile.put16(packet,4,part);int at=6;
                while(at<packet.length){int read=stream.read(packet,at,packet.length-at);if(read<0)throw new EOFException("表盘传输源截断");at+=read;}
                channel.send(packet);sent+=n;progress.update("传输表盘 "+part+" / "+total,(int)((long)sent*95/stream.length));
            }
        }
        progress.update("传输完成，等待手表安装结果",95);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
        while(true){
            Proto.Node result=await(4,5,Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))).node(6).node(7);
            if(!face.id.equals(result.text(1))){if(System.nanoTime()>deadline)throw new IOException("安装结果 ID 不匹配");continue;}
            long code=result.number(2);if(code!=2&&code!=3)throw new IOException("手表安装失败，结果码 "+code);
            installed=true;break;
        }
        apply(face.id);
    }
    private void apply(String id)throws Exception {
        progress.update("安装已确认，应用表盘",98);
        send(Proto.wear(4,1,6,new Proto().text(2,id)),true);
        if(await(4,1,20000).node(6).number(4)!=1)throw new IOException("已安装，但手表拒绝应用");
        if(!hasFace(id,true))throw new IOException("已安装，尚未从手表确认当前表盘");
        progress.update("手表已确认安装并设为当前表盘",100);
    }
    private boolean hasFace(String id,boolean current)throws Exception {
        send(Proto.wear(4,0,0,null),true);
        for(Proto.Node item:await(4,0,20000).node(6).node(1).nodes(1))
            if(id.equals(item.text(1))&&(!current||item.number(3)==1))return true;
        return false;
    }
    private void send(Proto p,boolean encrypted)throws Exception {
        if(encrypted&&!authenticated)throw new IOException("未认证，禁止发送设置命令");
        byte[] body=p.encode();if(encrypted)body=Crypto.ctr(enc,body);
        channel.send(Crypto.concat(new byte[]{1,(byte)(encrypted?2:1)},body));
    }
    private Proto.Node await(int type,int id,long timeout)throws Exception {
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
        while(true){
            for(int i=0;i<inbox.size();i++){Proto.Node p=inbox.get(i);if(p.number(1)==type&&p.number(2)==id){inbox.remove(i);return checked(p);}}
            long left=TimeUnit.NANOSECONDS.toMillis(until-System.nanoTime());if(left<=0)throw new IOException("等待手表响应超时（未确认成功）");
            byte[] raw=channel.receive(left);if(raw.length<2||raw[0]!=1)continue;
            int op=raw[1]&255;byte[] body=Arrays.copyOfRange(raw,2,raw.length);
            if(op==2){if(dec==null)throw new IOException("认证前收到加密数据");body=Crypto.ctr(dec,body);}
            else if(op!=1)continue;
            Proto.Node p=Proto.parse(body);
            if(p.number(1)==type&&p.number(2)==id)return checked(p);
            // Keep only installation events; unrelated sync messages must not exhaust memory.
            if(p.number(1)==4&&p.number(2)==5){if(inbox.size()>=16)throw new IOException("安装事件过多");inbox.add(p);}
        }
    }
    private static Proto.Node checked(Proto.Node p)throws IOException {if(p.has(100))throw new IOException("手表返回协议错误");return p;}
    private static void requireReady(long s)throws IOException {
        if(s==0)return;
        String[] names={"就绪","忙碌","已存在同 ID 表盘","存储不足","电量过低","禁止降级","不支持此操作","表盘数量已达上限","网络错误"};
        throw new IOException("手表未准备好："+(s>0&&s<names.length?names[(int)s]:"错误码 "+s));
    }
    @Override public void close()throws IOException {authenticated=false;targetChecked=false;if(enc!=null)Arrays.fill(enc,(byte)0);if(dec!=null)Arrays.fill(dec,(byte)0);channel.close();}
}
