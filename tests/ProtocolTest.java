package local.watchdesk.relay;

import java.io.*;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Host tests, including a scripted peer. These are not evidence of Q63 hardware compatibility. */
public final class ProtocolTest {
    private static int passed;
    private interface Check { void run() throws Exception; }
    private static void test(String name,Check check)throws Exception {check.run();passed++;System.out.println("PASS "+name);}
    private static void equal(byte[] a,byte[] b){if(!Arrays.equals(a,b))throw new AssertionError("Byte mismatch");}
    private static void yes(boolean b){if(!b)throw new AssertionError("Condition failed");}
    private static void fails(Check check)throws Exception {try{check.run();}catch(IOException|IllegalArgumentException|GeneralSecurityException expected){return;}throw new AssertionError("Expected failure");}
    private static byte[] range(int n){byte[] b=new byte[n];for(int i=0;i<n;i++)b[i]=(byte)i;return b;}
    public static void main(String[] args)throws Exception {
        test("RFCOMM falls back after socket read ret -1 and closes failed socket",()->{
            List<Boolean> modes=new ArrayList<>();List<TestSocket> sockets=new ArrayList<>();
            TestSocket chosen=ConnectionAttempts.open(secure->{modes.add(secure);TestSocket s=new TestSocket();sockets.add(s);return s;},s->{if(s==sockets.get(0))throw new IOException("read failed,socket might closed or timeout,read ret:-1");},()->false,s->{},(secure,attempt)->{},1000);
            yes(modes.equals(Arrays.asList(false,true)));yes(sockets.get(0).closed&&chosen==sockets.get(1)&&!chosen.closed);chosen.close();
        });
        test("RFCOMM timeout closes stalled socket before trying next mode",()->{
            List<TestSocket> sockets=new ArrayList<>();
            TestSocket chosen=ConnectionAttempts.open(secure->{TestSocket s=new TestSocket();sockets.add(s);return s;},s->{if(s==sockets.get(0)){try{yes(s.closeEvent.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IOException(e);}throw new IOException("closed");}},()->false,s->{},(secure,attempt)->{},30);
            yes(sockets.size()==2&&sockets.get(0).closed&&!chosen.closed);chosen.close();
        });
        test("RFCOMM cancellation closes socket and never starts fallback",()->{
            java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();List<TestSocket> sockets=new ArrayList<>();
            fails(()->ConnectionAttempts.open(secure->{TestSocket s=new TestSocket();sockets.add(s);return s;},s->{cancelled.set(true);throw new IOException("closed");},cancelled::get,s->{},(secure,attempt)->{},1000));yes(sockets.size()==1&&sockets.get(0).closed);
        });
        test("RFCOMM preserves both connection errors without claiming authentication failure",()->{
            try{ConnectionAttempts.open(secure->new TestSocket(),s->{throw new IOException("read ret:-1");},()->false,s->{},(secure,attempt)->{},1000);throw new AssertionError("expected error");}
            catch(IOException e){yes(e.getMessage().contains("兼容通道")&&e.getMessage().contains("配对通道")&&e.getMessage().contains("连接新手机")&&!e.getMessage().contains("AuthKey"));}
        });
        test("CRC16/ARC check vector",()->yes(SarLink.crc16("123456789".getBytes("US-ASCII"))==0xbb3d));
        test("SAR known frame",()->equal(SarLink.frame(3,7,new byte[]{1,2,3}),Crypto.unhex("a5a50307030010a1010203")));
        test("KDF independent Python HMAC vector",()->equal(Crypto.derive(range(16),Arrays.copyOfRange(range(32),16,32),Arrays.copyOfRange(range(48),32,48)),Crypto.unhex("d738074e6570abb50d001db70f497a37923e295e02aecb7619a8e1b9f574c9888676d22523869a15ee4340ac1bab5e35044aed283e7cace9aad41acb59fe75eb")));
        String[] ccms={"e9f64ce1","330fcc6a18","3314f164d885c2b6791ac3eb0ee78b3a8e9344","3314f164d885c2b6791ac3eb0ee78b8f0c91f636","3314f164d885c2b6791ac3eb0ee78b8f7c3424e17c","3314f164d885c2b6791ac3eb0ee78b8f7c470b21df11a12f567e5686ec3db5aed2646b3e30bb282a471965f19818c5f6984fe58bc39295318258fbc4e14b0f874280163a4b"};
        int[] lengths={0,1,15,16,17,65};
        for(int i=0;i<lengths.length;i++){final int index=i;test("CCM independent AESCCM vector length "+lengths[i],()->equal(Crypto.ccm(range(16),range(12),range(lengths[index])),Crypto.unhex(ccms[index])));}
        test("CTR independent OpenSSL-backed vector",()->equal(Crypto.ctr(range(16),range(65)),Crypto.unhex("0a9509b6456bf642f9ca9e53ca5ee4551272fe87720d648182c3e71457b911c33a0cb690353983df95ebe266e004c2687d3a99e1ad6aa9305a1a1e537b88ed31cd")));
        test("Proto explicit zero and unknown fields",()->{Proto.Node n=Proto.parse(new Proto().number(1,0).number(123,99).text(2,"Q63").encode());yes(n.has(1)&&n.number(1)==0&&n.text(2).equals("Q63"));fails(()->n.number(3));});
        test("Proto truncation, overflow, wrong type rejected",()->{fails(()->Proto.parse(new byte[]{10,4,1}));fails(()->Proto.parse(new byte[]{8,(byte)128}));fails(()->Proto.parse(new byte[]{0}));fails(()->Proto.parse(new byte[]{8,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,2}));fails(()->Proto.parse(new Proto().text(1,"0").encode()).number(1));});
        test("Connection import: plain log, repeated key, escaped JSON and boundary split",()->{
            String key=Crypto.hex(range(16));String field="\"encryptKey\":\""+key+"\"";String prefix=new String(new char[8185]).replace('\0',' ');
            equal(ConnectionImport.read(new ByteArrayInputStream((prefix+field+"\n"+field.replace("\"","\\\"")).getBytes("UTF-8"))),range(16));
        });
        test("Connection import: ZIP stream without extracting files",()->equal(ConnectionImport.read(new ByteArrayInputStream(zip("XiaomiFit.device.log",("\"encryptKey\":\""+Crypto.hex(range(16))+"\"").getBytes("UTF-8")))),range(16)));
        test("Connection import: ambiguous devices rejected",()->fails(()->ConnectionImport.read(new ByteArrayInputStream(("\"encryptKey\":\""+Crypto.hex(range(16))+"\" \"encryptKey\":\"11111111111111111111111111111111\"").getBytes("UTF-8")))));
        test("Connection import: zero key and unrelated account token rejected",()->{fails(()->ConnectionImport.read(new ByteArrayInputStream("\"encryptKey\":\"00000000000000000000000000000000\"".getBytes("UTF-8"))));fails(()->ConnectionImport.read(new ByteArrayInputStream("\"token\":\"11111111111111111111111111111111\"".getBytes("UTF-8"))));});
        test("Connection import: decompression limit prevents ZIP bomb",()->fails(()->ConnectionImport.read(new ByteArrayInputStream(zip("device.log",new byte[17*1024*1024])))));
        File temp=File.createTempFile("relay-test-",".face");
        try {
            byte[] faceBytes=range(4096);Arrays.fill(faceBytes,0,104,(byte)0);FaceFile.put32(faceBytes,0,0x1234a55aL);FaceFile.put32(faceBytes,4,65536);FaceFile.put32(faceBytes,16,0x800);System.arraycopy("941006207".getBytes("US-ASCII"),0,faceBytes,40,9);Files.write(temp.toPath(),faceBytes);FaceFile face=new FaceFile(temp);
            test("Q63 ID uses actual native offset 0x28",()->yes(face.id.equals("941006207")));
            for(int offset:new int[]{0,1234,4096})test("MASS streaming CRC and resume "+offset,()->verifyMass(face,faceBytes,offset));
            for(int offset:new int[]{-1,4097})test("MASS rejects resume offset "+offset,()->fails(()->face.mass(offset)));
            for(String mode:new String[]{"ok","resume","new-reply","legacy-q63"})test("Scripted peer install + apply + current readback "+mode,()->runPeer(face,mode,true));
            for(String mode:new String[]{"bad-sign","auth-false","wrong-device","legacy-ambiguous","prepare-busy","missing-ready","mass-md5","bad-resume","no-slice","compression","install-fail","no-result-code","wrong-result-id","apply-false","not-current","disconnect"})test("Scripted peer fails closed: "+mode,()->runPeer(face,mode,false));
            test("Invalid native header rejected",()->{byte[] bad=faceBytes.clone();bad[0]=0;Files.write(temp.toPath(),bad);fails(()->new FaceFile(temp));});
        } finally {temp.delete();}
        test("SAR stream: split/coalesced packets, NAK retry, CRC recovery, duplicate and 8-bit sequence wrap",ProtocolTest::sar);
        test("Q63 fallback never overrides conflicting product codename",()->fails(()->TargetProfile.verify(Proto.parse(new Proto().text(4,"M2606W1").text(5,"p62").encode()))));
        test("Missing device identity cannot authorize installation",()->fails(()->TargetProfile.verify(Proto.parse(new Proto().text(2,"4.101.020").encode()))));
        System.out.println("Passed "+passed+" protocol tests. Android runtime / physical watch: NOT TESTED.");
    }
    private static final class TestSocket implements Closeable {
        volatile boolean closed;final CountDownLatch closeEvent=new CountDownLatch(1);
        @Override public void close(){closed=true;closeEvent.countDown();}
    }
    private static byte[] zip(String name,byte[] content)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(out)){z.putNextEntry(new ZipEntry(name));z.write(content);z.closeEntry();}return out.toByteArray();}
    private static void verifyMass(FaceFile face,byte[] file,int offset)throws Exception {
        ByteArrayOutputStream all=new ByteArrayOutputStream();
        try(FaceFile.MassStream s=face.mass(offset)){byte[] b=new byte[37];int n;while((n=s.read(b))!=-1)all.write(b,0,n);yes(s.length==all.size());}
        byte[] actual=all.toByteArray();yes(actual[0]==0&&actual[1]==16);equal(Arrays.copyOfRange(actual,2,18),MessageDigest.getInstance("MD5").digest(file));yes(FaceFile.u32(actual,18)==file.length-offset);equal(Arrays.copyOfRange(actual,22,actual.length-4),Arrays.copyOfRange(file,offset,file.length));CRC32 crc=new CRC32();crc.update(actual,0,actual.length-4);yes(FaceFile.u32(actual,actual.length-4)==crc.getValue());
    }
    private static void runPeer(FaceFile face,String mode,boolean success)throws Exception {
        Peer peer=new Peer(face,mode);List<Integer> progress=new ArrayList<>();WatchClient client=new WatchClient(peer,(stage,percent)->progress.add(percent));
        try{Check action=()->{client.authenticate(range(16));client.install(face);};if(success){action.run();yes(client.installed&&progress.contains(100)&&peer.applied&&peer.listReads==1);}
            else{fails(action);yes(!progress.contains(100));if(mode.equals("apply-false")||mode.equals("not-current"))yes(client.installed);else yes(!client.installed);if(mode.equals("bad-sign")||mode.equals("auth-false")||mode.equals("wrong-device")||mode.equals("legacy-ambiguous"))yes(peer.prepares==0);}}
        finally{client.close();}
    }
    private static final class Peer implements Channel {
        final FaceFile face;final String mode;final Queue<byte[]> queue=new ArrayDeque<>();final ByteArrayOutputStream mass=new ByteArrayOutputStream();
        byte[] incoming,outgoing;boolean applied;int prepares,listReads,part=1,total;
        Peer(FaceFile face,String mode){this.face=face;this.mode=mode;}
        void reply(Proto p,boolean encrypted)throws Exception {byte[] b=p.encode();queue.add(Crypto.concat(new byte[]{1,(byte)(encrypted?2:1)},encrypted?Crypto.ctr(outgoing,b):b));}
        @Override public void send(byte[] p)throws IOException {
            try {
                if(mode.equals("disconnect"))throw new IOException("Peer disconnected");
                if(p[0]==2){yes(p[1]==1);yes(FaceFile.u16(p,4)==part++);if(total==0)total=FaceFile.u16(p,2);yes(total==FaceFile.u16(p,2));mass.write(p,6,p.length-6);
                    if(part==total+1){byte[] b=mass.toByteArray();int offset=mode.equals("resume")?1234:0;yes(FaceFile.u32(b,18)==face.size-offset);CRC32 crc=new CRC32();crc.update(b,0,b.length-4);yes(crc.getValue()==FaceFile.u32(b,b.length-4));equal(Arrays.copyOfRange(b,22,b.length-4),Arrays.copyOfRange(Files.readAllBytes(face.file.toPath()),offset,face.size));
                        Proto result=new Proto().text(1,mode.equals("wrong-result-id")?"other":face.id);if(!mode.equals("no-result-code"))result.number(2,mode.equals("install-fail")?1:2);reply(Proto.wear(4,5,6,new Proto().message(7,result)),true);}
                    return;}
                byte[] body=Arrays.copyOfRange(p,2,p.length);if(p[1]==2)body=Crypto.ctr(incoming,body);Proto.Node n=Proto.parse(body);int type=(int)n.number(1),id=(int)n.number(2);
                if(type==1&&id==26){yes(p[1]==1);byte[] phone=n.node(3).node(30).bytes(1),watch=Arrays.copyOfRange(range(48),32,48),keys=Crypto.derive(range(16),phone,watch);outgoing=Arrays.copyOfRange(keys,0,16);incoming=Arrays.copyOfRange(keys,16,32);byte[] sign=Crypto.hmac(outgoing,Crypto.concat(watch,phone));if(mode.equals("bad-sign"))sign[0]^=1;reply(Proto.wear(1,26,3,new Proto().message(31,new Proto().bytes(1,watch).bytes(2,sign))),false);}
                else if(type==1&&id==27){yes(p[1]==1);yes(n.node(3).node(32).bytes(1).length==32);reply(Proto.wear(1,27,3,new Proto().message(33,new Proto().number(1,mode.equals("auth-false")?0:1))),false);}
                else if(type==2&&id==2){yes(p[1]==2);Proto device=new Proto().text(4,mode.equals("legacy-q63")?"M2606W1":mode.equals("legacy-ambiguous")?"Xiaomi Watch S5":"Watch S5 41mm");if(!mode.startsWith("legacy-"))device.text(5,mode.equals("wrong-device")?"q64":"q63");reply(Proto.wear(2,2,4,new Proto().message(3,device)),true);}
                else if(type==4&&id==4){prepares++;Proto.Node info=n.node(6).node(6);yes(face.id.equals(info.text(1))&&info.number(2)==face.size);Proto wf=new Proto();if(mode.equals("new-reply"))wf.message(9,new Proto().text(1,face.id).number(2,0));else if(!mode.equals("missing-ready"))wf.number(5,mode.equals("prepare-busy")?1:0);reply(Proto.wear(4,4,6,wf),true);}
                else if(type==22&&id==0){equal(n.node(24).node(1).bytes(2),face.md5);Proto answer=new Proto().bytes(1,mode.equals("mass-md5")?new byte[16]:face.md5).number(2,0).number(3,mode.equals("compression")?1:0).number(4,mode.equals("bad-resume")?face.size+1:mode.equals("resume")?1234:0);if(!mode.equals("no-slice"))answer.number(5,1000);reply(Proto.wear(22,0,24,new Proto().message(2,answer)),true);}
                else if(type==4&&id==1){yes(n.node(6).text(2).equals(face.id));applied=true;reply(Proto.wear(4,1,6,new Proto().number(4,mode.equals("apply-false")?0:1)),true);}
                else if(type==4&&id==0){listReads++;reply(Proto.wear(4,0,6,new Proto().message(1,new Proto().message(1,new Proto().text(1,face.id).text(2,"L1D").number(3,mode.equals("not-current")?0:1)))),true);}
                else throw new AssertionError("Unexpected command "+type+":"+id);
            } catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}
        }
        @Override public byte[] receive(long timeout)throws IOException {byte[] p=queue.poll();if(p==null)throw new IOException("Peer response timeout");return p;}
        @Override public int maxPayload(){return 512;}
        @Override public void close(){}
    }
    private static byte[] readFrame(InputStream in)throws IOException {DataInputStream d=new DataInputStream(in);byte[] h=new byte[8];d.readFully(h);yes(h[0]==(byte)0xa5&&h[1]==(byte)0xa5);byte[] b=new byte[FaceFile.u16(h,4)];d.readFully(b);yes(SarLink.crc16(b)==FaceFile.u16(h,6));return Crypto.concat(h,b);}
    private static void sar()throws Exception {
        PipedInputStream phoneIn=new PipedInputStream(131072),watchIn=new PipedInputStream(131072);PipedOutputStream watchOut=new PipedOutputStream(phoneIn),phoneOut=new PipedOutputStream(watchIn);
        Closeable close=()->{phoneIn.close();watchIn.close();phoneOut.close();watchOut.close();};SarLink link=new SarLink(phoneIn,phoneOut,close);
        CompletableFuture<Void> peer=CompletableFuture.runAsync(()->{try{
            byte[] hello=new byte[11];new DataInputStream(watchIn).readFully(hello);equal(hello,Crypto.unhex("badcfe00c00300000100ef"));byte[] start=readFrame(watchIn);yes(start[2]==2);
            byte[] ready=SarLink.frame(2,0,new byte[]{2,2,2,0,0,8});for(byte b:ready){watchOut.write(b);watchOut.flush();}
            byte[] first=readFrame(watchIn);watchOut.write(SarLink.frame(0,0,new byte[0]));watchOut.flush();equal(first,readFrame(watchIn));watchOut.write(SarLink.frame(1,0,new byte[0]));watchOut.flush();
            byte[] data=SarLink.frame(3,0,new byte[]{1,1,8,2,16,2}),bad=data.clone();bad[6]^=1;watchOut.write(bad);watchOut.flush();yes(readFrame(watchIn)[2]==0);watchOut.write(data);watchOut.flush();yes(readFrame(watchIn)[2]==1);
            watchOut.write(Crypto.concat(data,SarLink.frame(3,1,new byte[]{1,1,8,4,16,5})));watchOut.flush();yes(readFrame(watchIn)[2]==1);yes(readFrame(watchIn)[2]==1);
            for(int i=1;i<260;i++){byte[] sent=readFrame(watchIn);yes((sent[3]&255)==(i&255));watchOut.write(SarLink.frame(1,i&255,new byte[0]));watchOut.flush();}
        }catch(Exception e){throw new CompletionException(e);}});
        try{link.start();yes(link.maxPayload()==2048);link.send(new byte[]{1,1,0});equal(link.receive(2000),new byte[]{1,1,8,2,16,2});equal(link.receive(2000),new byte[]{1,1,8,4,16,5});for(int i=1;i<260;i++)link.send(new byte[]{1,1,0});peer.get(10,TimeUnit.SECONDS);}
        finally{link.close();}
    }
}
