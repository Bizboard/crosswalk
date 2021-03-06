// Copyright (c) 2013-2015 Intel Corporation. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.xwalk.core.internal;

import java.util.HashMap;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.content.Context;
import android.app.Activity;
import android.app.Presentation;
import android.hardware.display.DisplayManager;
import android.view.Display;

import org.chromium.base.annotations.JNINamespace;
import org.chromium.base.annotations.CalledByNative;

import org.xwalk.core.internal.extension.api.XWalkDisplayManager;

@JNINamespace("xwalk")
class XWalkPresentationHost implements XWalkDisplayManager.DisplayListener {
    private static String TAG = "XWalkPresentationHost";
    private static Activity sEnduringActivity = null;
    private static XWalkPresentationHost sInstance;

    public class RenderFrameHostId {
        public RenderFrameHostId(int renderProcessID, int renderFrameID) {
            this.renderProcessID = renderProcessID;
            this.renderFrameID = renderFrameID;
        }

        @Override
        public int hashCode() {
            int hash = 17 + this.renderProcessID;
            hash *= 31;
            hash += this.renderFrameID;
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof RenderFrameHostId) {
                if (obj == this) {
                    return true;
                } else {
                    RenderFrameHostId that = (RenderFrameHostId)obj;
                    return this.renderProcessID == that.renderProcessID
                        && this.renderFrameID == that.renderFrameID;
                }
            }
            return false;
        }

        public int renderProcessID;
        public int renderFrameID;
    };

    private final class PresentationSession {
        public PresentationSession(Context context, Activity activity, int renderProcessID, int renderFrameID) {
            this.context = context;
            this.activity = activity;
            this.renderProcessID = renderProcessID;
            this.renderFrameID = renderFrameID;
            this.presentationScreen = null;
        }

        public PresentationScreen presentationScreen;
        public Context context;
        public Activity activity;
        public int renderProcessID;
        public int renderFrameID;
    };

    private HashMap<RenderFrameHostId, PresentationSession> mExistingSessions;
    private long mNativePresentationHost;
    private XWalkDisplayManager mDisplayManager;

    public static XWalkPresentationHost createInstanceOnce(Activity activity, Context context) {
        if (sInstance == null) {
            sInstance = new XWalkPresentationHost(context);
            sInstance.saveActivityOnce(activity);
        }
        return sInstance;
    }

    private void saveActivityOnce(Activity activity) {
        if (sEnduringActivity == null) {
            sEnduringActivity = activity;
        }
    }

    private PresentationSession createNewSession(RenderFrameHostId id) {
        PresentationSession session = new PresentationSession(sEnduringActivity, sEnduringActivity, id.renderProcessID, id.renderFrameID);
        assert mExistingSessions.get(id) == null;
        mExistingSessions.put(id, session);
        return session;
    }

    private void removeContextActivity(final int renderProcessID, final int renderFrameID) {
        RenderFrameHostId id = new RenderFrameHostId(renderProcessID, renderFrameID);
        mExistingSessions.remove(id);
    }

    private boolean startNewSession(PresentationSession session, final int displayId, final String url) {
        if (session != null) {
            Display[] presentationDisplays = {};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                presentationDisplays = mDisplayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            }
            if (presentationDisplays.length > 0) {
                Display display = null;
                for (Display query : presentationDisplays) {
                    if (query.getDisplayId() == displayId) {
                        display = query;
                    }
                }

                if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    session.presentationScreen = new PresentationScreen(session.context, session.activity, display);
                    session.presentationScreen.show();
                    session.presentationScreen.loadUrl(url);
                    return true;
                } else {
                    Log.e(TAG, "Can't find specified display with id " + displayId);
                }

            }
        }

        Log.e(TAG, "startNewSession falied!");
        return false;
    }

    private void closeSession(final int renderProcessID, final int renderFrameID) {
        RenderFrameHostId id = new RenderFrameHostId(renderProcessID, renderFrameID);
        PresentationSession session = mExistingSessions.get(id);
        if (session != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            if (session.presentationScreen != null) {
                session.presentationScreen.dismiss();
                session.presentationScreen = null;
                this.nativeOnPresentationClosed(mNativePresentationHost, renderProcessID, renderFrameID);
            }
            removeContextActivity(renderProcessID, renderFrameID);
        }
    }

    private XWalkPresentationHost (Context context) {
        mExistingSessions = new HashMap<RenderFrameHostId, PresentationSession>();
        mDisplayManager = XWalkDisplayManager.getInstance(context);
        setNativeObject(nativeInit());
        listenToSystemDisplayChange();
    }

    public void listenToSystemDisplayChange() {
        mDisplayManager.registerDisplayListener(this);
    }

    public void stopListenToSystemDisplayChange() {
        mDisplayManager.unregisterDisplayListener(this);
    }

    @CalledByNative
    public Display[] getAndroidDisplayInfo() {
        final Display[] emptyDisplay = {};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
            return mDisplayManager.getDisplays();
        else
            return emptyDisplay;
    }

    @CalledByNative
    public boolean showPresentation(final int renderProcessID, final int renderFrameID,
        final int displayId, final String url) {
        RenderFrameHostId id = new RenderFrameHostId(renderProcessID, renderFrameID);
        PresentationSession session = mExistingSessions.get(id);
        if (session == null) {
            session = this.createNewSession(id);
        }
        return this.startNewSession(session, displayId, url);
    }

    @CalledByNative
    public void closePresentation(int renderProcessID, int renderFrameID) {
        this.closeSession(renderProcessID, renderFrameID);
    }

    private void setNativeObject(long newNativePresentationAPI) {
        assert mNativePresentationHost == 0;
        mNativePresentationHost = newNativePresentationAPI;

        nativeSetupJavaPeer(mNativePresentationHost);
    }

    private native long nativeInit();
    private static native void nativeDestroy(long nativeXWalkPresentationHost);
    private native void nativeSetupJavaPeer(long nativeXWalkPresentationHost);
    private native void nativeOnPresentationClosed(long nativeXWalkPresentationHost, int renderProcessID, int renderFrameID);

    private native void nativeOnDisplayAdded(long nativeXWalkPresentationHost, int displayId);
    private native void nativeOnDisplayChanged(long nativeXWalkPresentationHost, int displayId);
    private native void nativeOnDisplayRemoved(long nativeXWalkPresentationHost, int displayId);

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private final class PresentationScreen extends Presentation {
        private XWalkViewInternal mContentView;
        private Context mContext;
        private Display mDisplay;
        private Activity mActivity;

        public PresentationScreen(Context context, Activity activity, Display display) {
            super(context, display);

            mContext = context;
            mDisplay = display;
            mActivity = activity;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            if (mContentView == null) {
                mContentView = new XWalkViewInternal(getContext(), mActivity);
                mContentView.setUIClient(new XWalkUIClientInternal(mContentView));
            }
            setContentView(mContentView);
        }

        public void loadUrl(final String url) {
            mContentView.load(url, null);
        }
    }


    @Override
    public void onDisplayAdded(int displayId) {
        this.nativeOnDisplayAdded(mNativePresentationHost, displayId);
    }

    @Override
    public void onDisplayChanged(int displayId) {
        this.nativeOnDisplayChanged(mNativePresentationHost, displayId);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        this.nativeOnDisplayRemoved(mNativePresentationHost, displayId);
    }

};
