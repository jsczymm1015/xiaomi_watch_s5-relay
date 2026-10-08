package local.watchdesk.relay;
import java.io.Closeable;
import java.io.IOException;
public interface Channel extends Closeable {
    void send(byte[] payload) throws IOException, InterruptedException;
    byte[] receive(long timeoutMs) throws IOException, InterruptedException;
    int maxPayload();
}
