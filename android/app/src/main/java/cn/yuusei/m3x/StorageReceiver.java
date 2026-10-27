package cn.yuusei.m3x;

import android.content.*;

public final class StorageReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        PlayerApp app = PlayerApp.instance;
        if (!Intent.ACTION_MEDIA_MOUNTED.equals(intent.getAction())) {
            app.library.stop();
            if (app.playback != null) app.playback.pause();
        }
    }
}
