package local.watchdesk.relay;

import java.io.*;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** Tries Android's two public RFCOMM modes, before application authentication. */
public final class ConnectionAttempts {
    public interface Factory<T> { T create(boolean secure)throws IOException; }
    public interface Dial<T> { void connect(T socket)throws IOException; }
    public interface Notice { void trying(boolean secure,int attempt); }
    public static <T extends Closeable> T open(Factory<T> factory,Dial<T> dial,BooleanSupplier cancelled,
            Consumer<T> current,Notice notice,long timeoutMs)throws IOException {
        ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"rfcomm-timeout");t.setDaemon(true);return t;});
        StringBuilder errors=new StringBuilder();
        try {
            for(int attempt=0;attempt<2;attempt++) {
                check(cancelled);boolean secure=attempt==1;notice.trying(secure,attempt+1);
                T socket=null;ScheduledFuture<?> timeout=null;AtomicInteger state=new AtomicInteger();
                try {
                    socket=factory.create(secure);current.accept(socket);check(cancelled);
                    final T active=socket;
                    timeout=timer.schedule(()->{if(state.compareAndSet(0,2))close(active);},timeoutMs,TimeUnit.MILLISECONDS);
                    dial.connect(socket);check(cancelled);
                    if(!state.compareAndSet(0,1))throw new SocketTimeoutException("连接超时");
                    return socket;
                } catch(IOException e) {
                    check(cancelled);
                    if(errors.length()>0)errors.append("；");
                    errors.append(secure?"配对通道":"兼容通道").append(": ").append(state.get()==2?"超时":e.getMessage());
                } finally {
                    if(timeout!=null)timeout.cancel(false);
                    if(state.get()!=1){close(socket);current.accept(null);}
                }
            }
            throw new IOException("蓝牙通道未建立（"+errors+"）。请让手表进入「连接新手机」页面，并暂时关闭运动健康的连接后重试。不要恢复出厂。");
        } finally {timer.shutdownNow();}
    }
    private static void check(BooleanSupplier cancelled)throws InterruptedIOException {if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("连接已取消");}
    private static void close(Closeable c){if(c!=null)try{c.close();}catch(IOException ignored){}}
}
