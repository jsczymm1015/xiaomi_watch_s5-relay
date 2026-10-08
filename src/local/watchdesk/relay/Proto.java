package local.watchdesk.relay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Minimal, bounded proto2 wire codec. Required values never silently default to zero. */
public final class Proto {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    public Proto number(int field, long value) { varint((long)field << 3); varint(value); return this; }
    public Proto bytes(int field, byte[] value) { varint(((long)field << 3) | 2); varint(value.length); out.write(value, 0, value.length); return this; }
    public Proto text(int field, String value) { return bytes(field, value.getBytes(StandardCharsets.UTF_8)); }
    public Proto message(int field, Proto value) { return bytes(field, value.encode()); }
    public byte[] encode() { return out.toByteArray(); }
    private void varint(long n) { while ((n & ~127L) != 0) { out.write((int)n & 127 | 128); n >>>= 7; } out.write((int)n); }
    public static Proto wear(int type, int id, int field, Proto payload) {
        Proto p = new Proto().number(1, type).number(2, id);
        return payload == null ? p : p.message(field, payload);
    }
    public static Node parse(byte[] bytes) throws IOException { return new Node(bytes); }
    public static final class Node {
        private final Map<Integer, List<Object>> fields = new HashMap<>();
        Node(byte[] bytes) throws IOException {
            if (bytes.length > 65535) throw new IOException("Protobuf 包过大");
            int[] pos = {0};
            while (pos[0] < bytes.length) {
                long tag = read(bytes, pos); int field = (int)(tag >>> 3), wire = (int)(tag & 7);
                if (field <= 0 || field > 0x1fffffff || tag >>> 32 != 0) throw new IOException("无效 Protobuf 字段");
                Object value;
                if (wire == 0) value = read(bytes, pos);
                else if (wire == 2) {
                    long n = read(bytes, pos);
                    if (n < 0 || n > bytes.length - pos[0]) throw new IOException("Protobuf 长度越界");
                    value = Arrays.copyOfRange(bytes, pos[0], pos[0] + (int)n); pos[0] += (int)n;
                } else if (wire == 1 || wire == 5) {
                    int n = wire == 1 ? 8 : 4;
                    if (n > bytes.length - pos[0]) throw new IOException("Protobuf 定长字段截断");
                    pos[0] += n; continue;
                } else throw new IOException("不支持的 Protobuf 编码");
                List<Object> list = fields.get(field);
                if (list == null) { list = new ArrayList<>(); fields.put(field, list); }
                list.add(value);
            }
        }
        private Object get(int field) throws IOException {
            List<Object> list = fields.get(field);
            if (list == null || list.isEmpty()) throw new IOException("手表响应缺少字段 " + field);
            return list.get(list.size() - 1);
        }
        public boolean has(int field) { return fields.containsKey(field); }
        public Set<Integer> fieldNumbers() { return Collections.unmodifiableSet(new TreeSet<>(fields.keySet())); }
        public long number(int field) throws IOException {
            Object v = get(field); if (!(v instanceof Long)) throw new IOException("字段类型不符"); return (Long)v;
        }
        public byte[] bytes(int field) throws IOException {
            Object v = get(field); if (!(v instanceof byte[])) throw new IOException("字段类型不符"); return (byte[])v;
        }
        public String text(int field) throws IOException { return new String(bytes(field), StandardCharsets.UTF_8); }
        public Node node(int field) throws IOException { return parse(bytes(field)); }
        public List<Node> nodes(int field) throws IOException {
            List<Node> result = new ArrayList<>();
            for (Object item : fields.getOrDefault(field, Collections.emptyList())) {
                if (!(item instanceof byte[])) throw new IOException("列表字段类型不符");
                result.add(parse((byte[])item));
            }
            return result;
        }
        private static long read(byte[] b, int[] p) throws IOException {
            long n = 0;
            for (int i=0; i<10; i++) {
                if (p[0] == b.length) throw new IOException("Protobuf varint 截断");
                int v = b[p[0]++] & 255;
                if (i == 9 && v > 1) throw new IOException("Protobuf varint 溢出");
                n |= (long)(v & 127) << (i*7);
                if ((v & 128) == 0) return n;
            }
            throw new IOException("Protobuf varint 溢出");
        }
    }
}
