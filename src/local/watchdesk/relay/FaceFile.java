package local.watchdesk.relay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.zip.CRC32;

public final class FaceFile {
    public static final int MAX_BYTES=64*1024*1024;
    public final File file;
    public final String id, sha256;
    public final byte[] md5;
    public final int size;
    public FaceFile(File file) throws IOException, GeneralSecurityException {
        this.file=file;
        if(file.length()<2048 || file.length()>MAX_BYTES) throw new IOException("表盘大小须为 2KB–64MB");
        size=(int)file.length();
        byte[] h=new byte[64];
        try(DataInputStream in=new DataInputStream(new FileInputStream(file))) { in.readFully(h); }
        if(u32(h,0)!=0x1234a55aL || u32(h,16)!=0x800 || u32(h,4)!=65536) throw new IOException("只接受 Q63 原生 0x800 表盘");
        // Q63 native builder stores the ID at 0x28, not the older 34-byte profile offset.
        int end=40; while(end<64 && h[end]!=0) end++;
        id=new String(h,40,end-40,StandardCharsets.US_ASCII);
        if(!id.matches("[0-9]{1,24}")) throw new IOException("表盘 ID 无效");
        MessageDigest m=MessageDigest.getInstance("MD5"), s=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)) {
            byte[] b=new byte[32768]; int n; while((n=in.read(b))!=-1){m.update(b,0,n);s.update(b,0,n);}
        }
        md5=m.digest();sha256=Crypto.hex(s.digest());
    }
    public MassStream mass(int offset) throws IOException { return new MassStream(offset); }
    public final class MassStream extends InputStream {
        private final FileInputStream in;
        private final byte[] header=Crypto.concat(new byte[]{0,16},md5,new byte[4]);
        private final CRC32 crc=new CRC32();
        private byte[] trailer;
        private int position, remaining;
        public final int length;
        MassStream(int offset) throws IOException {
            if(offset<0 || offset>size) throw new IOException("手表续传位置越界");
            remaining=size-offset;length=22+remaining+4;put32(header,18,remaining);
            in=new FileInputStream(file);in.getChannel().position(offset);
        }
        @Override public int read() throws IOException { byte[] b=new byte[1];return read(b,0,1)==-1?-1:b[0]&255; }
        @Override public int read(byte[] b,int off,int len) throws IOException {
            if(off<0 || len<0 || len>b.length-off) throw new IndexOutOfBoundsException();
            if(len==0)return 0;
            if(position<22){int n=Math.min(len,22-position);System.arraycopy(header,position,b,off,n);crc.update(b,off,n);position+=n;return n;}
            if(remaining>0){int n=in.read(b,off,Math.min(len,remaining));if(n<0)throw new EOFException("表盘读取中断");remaining-=n;position+=n;crc.update(b,off,n);return n;}
            if(trailer==null){trailer=new byte[4];put32(trailer,0,crc.getValue());}
            int at=position-(length-4);if(at>=4)return -1;int n=Math.min(len,4-at);System.arraycopy(trailer,at,b,off,n);position+=n;return n;
        }
        @Override public void close() throws IOException { in.close(); }
    }
    public static int u16(byte[] b,int at){return (b[at]&255)|((b[at+1]&255)<<8);}
    public static long u32(byte[] b,int at){return (long)u16(b,at)|(long)u16(b,at+2)<<16;}
    public static void put16(byte[] b,int at,int n){b[at]=(byte)n;b[at+1]=(byte)(n>>>8);}
    public static void put32(byte[] b,int at,long n){put16(b,at,(int)n);put16(b,at+2,(int)(n>>>16));}
}
