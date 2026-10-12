package android.net;

import java.net.InetAddress;

/** An IP address with its prefix length, as the platform's. */
public class LinkAddress implements android.os.Parcelable {
    public static final long LIFETIME_UNKNOWN = -1L, LIFETIME_PERMANENT = Long.MAX_VALUE;
    private final InetAddress mAddress;
    private final int mPrefix, mFlags, mScope;
    public LinkAddress(InetAddress address, int prefixLength) { this(address, prefixLength, 0, 0); }
    public LinkAddress(InetAddress address, int prefixLength, int flags, int scope) {
        if (address == null || address.isMulticastAddress()) throw new IllegalArgumentException("Bad LinkAddress params " + address);
        int max = address instanceof java.net.Inet4Address ? 32 : 128;
        if (prefixLength < 0 || prefixLength > max) throw new IllegalArgumentException("Bad LinkAddress params " + address + "/" + prefixLength);
        mAddress = address; mPrefix = prefixLength; mFlags = flags; mScope = scope;
    }
    public LinkAddress(java.net.InterfaceAddress ia) { this(ia.getAddress(), ia.getNetworkPrefixLength()); }
    public LinkAddress(String address) { this(address, 0, 0); }
    public LinkAddress(String address, int flags, int scope) {
        int slash = address.indexOf('/');
        InetAddress a; int prefix;
        try { a = InetAddress.getByName(slash < 0 ? address : address.substring(0, slash)); } catch (java.net.UnknownHostException e) { throw new IllegalArgumentException("Bad LinkAddress params " + address); }
        prefix = slash < 0 ? (a instanceof java.net.Inet4Address ? 32 : 128) : Integer.parseInt(address.substring(slash + 1));
        if (a.isMulticastAddress() || prefix < 0 || prefix > (a instanceof java.net.Inet4Address ? 32 : 128)) throw new IllegalArgumentException("Bad LinkAddress params " + address);
        mAddress = a; mPrefix = prefix; mFlags = flags; mScope = scope;
    }
    public InetAddress getAddress() { return mAddress; }
    public int getPrefixLength() { return mPrefix; }
    public int getNetworkPrefixLength() { return mPrefix; }
    public int getFlags() { return mFlags; }
    public int getScope() { return mScope; }
    public boolean isSameAddressAs(LinkAddress o) { return o != null && mAddress.equals(o.mAddress) && mPrefix == o.mPrefix; }
    public boolean isGlobalPreferred() { return !mAddress.isLinkLocalAddress() && !mAddress.isSiteLocalAddress() && !mAddress.isLoopbackAddress(); }
    public boolean isIpv4() { return mAddress instanceof java.net.Inet4Address; }
    public boolean isIpv6() { return mAddress instanceof java.net.Inet6Address; }
    @Override public boolean equals(Object o) { return o instanceof LinkAddress && isSameAddressAs((LinkAddress) o) && mFlags == ((LinkAddress) o).mFlags && mScope == ((LinkAddress) o).mScope; }
    @Override public int hashCode() { return mAddress.hashCode() * 31 + mPrefix; }
    @Override public String toString() { return mAddress.getHostAddress() + "/" + mPrefix; }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel p, int flags) { p.writeByteArray(mAddress.getAddress()); p.writeInt(mPrefix); p.writeInt(mFlags); p.writeInt(mScope); }
    public static final Creator<LinkAddress> CREATOR = new Creator<LinkAddress>() {
        public LinkAddress createFromParcel(android.os.Parcel p) { try { return new LinkAddress(InetAddress.getByAddress(p.createByteArray()), p.readInt(), p.readInt(), p.readInt()); } catch (java.net.UnknownHostException e) { throw new IllegalArgumentException(e); } }
        public LinkAddress[] newArray(int n) { return new LinkAddress[n]; }
    };
}
