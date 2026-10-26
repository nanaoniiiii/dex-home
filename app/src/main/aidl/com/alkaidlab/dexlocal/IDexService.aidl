package com.alkaidlab.dexlocal;
import android.view.Surface;
import android.view.MotionEvent;
interface IDexService {
    void attachOwner(IBinder owner) = 0;
    String startDex() = 1;
    String snapshot() = 2;
    void showDesktop(in Surface surface, int width, int height) = 3;
    void hideDesktop() = 4;
    void setHighRefresh(boolean enabled) = 5;
    oneway void touch(in MotionEvent event) = 6;
    boolean key(int keyCode, int action, int metaState) = 7;
    void stopDex() = 9;
    void makeFullscreen(int taskId) = 10;
    void enableHome() = 11;
    void showDexHome() = 12;
    void startService() = 13;
    void openDexPicker() = 14;
    void confirmHomeVisible(int taskId) = 15;
    void destroy() = 16777114;
}
