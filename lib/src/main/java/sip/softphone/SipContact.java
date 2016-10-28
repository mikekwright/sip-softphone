package sip.softphone;

public class SipContact {
    private String sipAddress;
    private String contact;
    private String displayName;
    private String tag;

    public String getSipAddress() {
        return sipAddress;
    }

    public void setSipAddress(String sipAddress) {
        this.sipAddress = sipAddress;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    public static SipContact createContact(final String address, final String contact) {
        return createContact(address, contact, null, null);
    }

    public static SipContact createContact(final String address, final String contact, final String displayName, final String tag) {
        SipContact sipContact = new SipContact();
        sipContact.setSipAddress(address);
        sipContact.setContact(contact);
        sipContact.setDisplayName(displayName);
        sipContact.setTag(tag);
        return sipContact;
    }
}
