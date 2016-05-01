package com.android.systemui.recents;

public class SearchItem {
    public static final int TYPE_APPLICATION = 1;
    public static final int TYPE_MUSIC = 2;
    public static final int TYPE_PHOTO = 3;
    public static final int TYPE_FILE = 4;
    public static final int TYPE_CONTACT = 5;
    public static final int TYPE_CALENDAR = 6;

    /**
     * Application:
     * LaunchInfo: the package name of the app
     * MoreLaunchInfo: the activity name of the launcher activity
     *
     * Contact:
     * LaunchInfo: the contact ID
     *
     * Music:
     * LaunchInfo: the track ID
     *
     * Calendar:
     * LaunchInfo: the event ID
     *
     * File:
     * LaunchInfo: the full path of the file
     * MoreLaunchInfo: folder if a folder, file otherwise
     */

    private int mType;
    private String mLabel;
    private String mSubLabel;
    private String mLaunchInfo;
    private String mMoreLaunchInfo;

    public SearchItem(int type, String label, String subLabel, String launchInfo, String moreLaunchInfo) {
        mType = type;
        mLabel = label;
        mSubLabel = subLabel;
        mLaunchInfo = launchInfo;
        mMoreLaunchInfo = moreLaunchInfo;
    }

    public String getLaunchInfo() {
        return mLaunchInfo;
    }

    public String getSubLabel() {
        return mSubLabel;
    }

    public String getLabel() {
        return mLabel;
    }

    public int getType() {
        return mType;
    }

    public String getMoreLaunchInfo() {
        return mMoreLaunchInfo;
    }

    public static class Comparator implements java.util.Comparator<SearchItem> {

        @Override
        public int compare(SearchItem searchItem, SearchItem t1) {
            return searchItem.mLabel.toUpperCase().compareTo(t1.mLabel.toUpperCase());
        }
    }
}