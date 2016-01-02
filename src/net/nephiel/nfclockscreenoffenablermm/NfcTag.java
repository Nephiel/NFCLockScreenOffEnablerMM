package net.nephiel.nfclockscreenoffenablermm;

import android.os.Parcel;
import android.os.Parcelable;

public class NfcTag implements Parcelable {
	private String mNfcTagName = null;
	private String mNfcTagId = null;

	public NfcTag() {

	}

	public NfcTag(String tagId, String tagName) {
		setTagId(tagId);
		setTagName(tagName);
	}

	public String getTagName() {
		return mNfcTagName;
	}

	public void setTagName(String name) {
		mNfcTagName = name;
	}

	public String getTagId() {
		return mNfcTagId;
	}

	public void setTagId(String id) {
		mNfcTagId = id;
	}

	public void setTagId(byte[] uuid) {
		mNfcTagId = Common.byteArrayToHexString(uuid);
	}

	public NfcTag (Parcel in) {
		String[] data = new String[2];

		in.readStringArray(data);
		mNfcTagName = data[0];
		mNfcTagId = data[1];
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {	
		dest.writeStringArray(new String[] { mNfcTagName, mNfcTagId } );
	}

	public static final Parcelable.Creator<NfcTag> CREATOR = new Parcelable.Creator<NfcTag>() {
		public NfcTag createFromParcel(Parcel in) {
			return new NfcTag(in); 
		}

		public NfcTag[] newArray(int size) {
			return new NfcTag[size];
		}
	};
}
