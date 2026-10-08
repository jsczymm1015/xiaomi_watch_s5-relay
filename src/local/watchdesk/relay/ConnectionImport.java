package local.watchdesk.relay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Reads only the user-selected export, never extracts or retains its log files. */
public final class ConnectionImport {
    private static final long INPUT_LIMIT=32L*1024*1024, OUTPUT_LIMIT=64L*1024*1024, ENTRY_LIMIT=16L*1024*1024;
    private static final Pattern KEY=Pattern.compile("\"encryptKey\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");
    public static byte[] read(InputStream source)throws IOException {
        Set<String> found=new LinkedHashSet<>();
        try(PushbackInputStream in=new PushbackInputStream(new Limited(source),4)) {
            byte[] magic=new byte[4];int n=0,r;while(n<4&&(r=in.read(magic,n,4-n))!=-1)n+=r;in.unread(magic,0,n);
            if(n>=2&&magic[0]=='P'&&magic[1]=='K') {
                try(ZipInputStream zip=new ZipInputStream(in)) {
                    ZipEntry entry;int count=0;long total=0;
                    while((entry=zip.getNextEntry())!=null) {
                        if(++count>256)throw new IOException("连接日志条目过多，请重新导出");
                        String name=entry.getName().toLowerCase(Locale.ROOT);
                        boolean text=name.endsWith(".log")||name.endsWith(".txt")||name.endsWith(".json");
                        byte[] buffer=new byte[8192];long size=0;String tail="";
                        while((r=zip.read(buffer))!=-1) {
                            size+=r;total+=r;
                            if(size>ENTRY_LIMIT||total>OUTPUT_LIMIT)throw new IOException("连接日志解压大小超过限制");
                            if(text)tail=scan(tail,buffer,r,found);
                        }
                        zip.closeEntry();
                    }
                }
            } else {
                byte[] buffer=new byte[8192];String tail="";long size=0;
                while((r=in.read(buffer))!=-1){size+=r;if(size>ENTRY_LIMIT)throw new IOException("连接日志过大");tail=scan(tail,buffer,r,found);}
            }
        }
        if(found.isEmpty())throw new IOException("未找到连接认证信息，请先在运动健康同步手表并导出新日志");
        if(found.size()!=1)throw new IOException("日志中有多个不同的设备密钥，无法安全自动选择；请导出仅含当前绑定的新日志，或使用高级手动输入");
        return Crypto.unhex(found.iterator().next());
    }
    private static String scan(String tail,byte[] bytes,int n,Set<String> found)throws IOException {
        String raw=tail+new String(bytes,0,n,StandardCharsets.UTF_8);
        Matcher matches=KEY.matcher(raw.replace("\\\"","\""));
        while(matches.find()){
            String value=matches.group(1).toLowerCase(Locale.ROOT);
            if(!value.equals("00000000000000000000000000000000"))found.add(value);
            if(found.size()>16)throw new IOException("连接日志包含过多设备密钥");
        }
        return raw.substring(Math.max(0,raw.length()-256));
    }
    private static final class Limited extends FilterInputStream {
        long bytes;
        Limited(InputStream source){super(source);}
        private void add(int n)throws IOException {if(n>0){bytes+=n;if(bytes>INPUT_LIMIT)throw new IOException("连接日志文件超过 32MB");}}
        @Override public int read()throws IOException {int n=in.read();if(n!=-1)add(1);return n;}
        @Override public int read(byte[] b,int off,int n)throws IOException {int r=in.read(b,off,n);add(r);return r;}
    }
}
