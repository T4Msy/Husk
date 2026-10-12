package android.os;

import java.nio.ByteBuffer;

/** Anonymous shared memory: a direct buffer of the size asked for (there is no other process to share it with here). */
public final class SharedMemory implements Parcelable, java.io.Closeable {

    private ByteBuffer mBuffer;
    private final int mSize;
    private final String mName;
    private int mProt = 7;     /* PROT_READ | PROT_WRITE | PROT_EXEC */
    private SharedMemory(String name, int size) { mName = name; mSize = size; mBuffer = ByteBuffer.allocateDirect(size); }
    public static SharedMemory create(String name, int size) {
        if (size <= 0) throw new IllegalArgumentException("Size must be greater than zero");
        return new SharedMemory(name, size);
    }
    public static SharedMemory fromFileDescriptor(ParcelFileDescriptor fd) { throw new IllegalArgumentException("SharedMemory cannot wrap a file descriptor here"); }
    public static void unmap(ByteBuffer buffer) {}
    public int getSize() { return mSize; }
    public boolean setProtect(int prot) { if ((prot & ~mProt) != 0) return false; mProt = prot; return true; }
    public ByteBuffer map(int prot, int offset, int length) {
        if (mBuffer == null) throw new IllegalStateException("SharedMemory is closed");
        if ((prot & ~mProt) != 0) throw new IllegalArgumentException("protection not allowed");
        if (offset < 0 || length < 0 || offset + length > mSize) throw new IllegalArgumentException("offset/length out of range");
        ByteBuffer b = mBuffer.duplicate(); b.position(offset); b.limit(offset + length);
        ByteBuffer s = b.slice().order(java.nio.ByteOrder.nativeOrder());
        return (prot & 2) == 0 ? s.asReadOnlyBuffer() : s;
    }
    public ByteBuffer mapReadWrite() { return map(3, 0, mSize); }
    public ByteBuffer mapReadOnly() { return map(1, 0, mSize); }
    public int getFd() { return -1; }
    public ParcelFileDescriptor getFdDup() throws java.io.IOException { throw new java.io.IOException("SharedMemory has no file descriptor here"); }
    public java.io.FileDescriptor getFileDescriptor() { return new java.io.FileDescriptor(); }
    public void close() { mBuffer = null; }
    public int describeContents() { return 0; }
    public void writeToParcel(Parcel p, int flags) { p.writeString(mName); p.writeInt(mSize); byte[] d = new byte[mSize]; if (mBuffer != null) { ByteBuffer b = mBuffer.duplicate(); b.rewind(); b.get(d); } p.writeByteArray(d); }
    public static final Creator<SharedMemory> CREATOR = new Creator<SharedMemory>() {
        public SharedMemory createFromParcel(Parcel p) { String n = p.readString(); int size = p.readInt(); SharedMemory m = new SharedMemory(n, size); byte[] d = p.createByteArray(); if (d != null) m.mBuffer.duplicate().put(d, 0, Math.min(d.length, size)); return m; }
        public SharedMemory[] newArray(int n) { return new SharedMemory[n]; }
    };
}
