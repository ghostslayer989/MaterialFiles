/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import org.apache.ftpserver.ftplet.FtpException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java8.nio.file.Path;
import me.zhanghai.android.files.settings.Settings;
import me.zhanghai.android.files.util.AppUtils;
import me.zhanghai.android.files.util.ToastUtils;

public class FtpServerService extends Service {

    public enum State {
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }

    public static final String USERNAME_ANONYMOUS = "anonymous";

    @Nullable
    private static FtpServerService sInstance;

    @NonNull
    private static final MutableLiveData<State> sStateLiveData = new MutableLiveData<>(
            State.STOPPED);

    @NonNull
    private final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();

    private FtpServerWakeLock mWakeLock;

    private State mState = State.STOPPED;

    private FtpServer mServer;

    public static void start(@NonNull Context context) {
        if (sInstance != null) {
            sInstance.submitStart();
        } else {
            context.startService(new Intent(context, FtpServerService.class));
        }
    }

    public static void stop() {
        if (sInstance != null) {
            sInstance.submitStop();
        }
    }

    @NonNull
    public static LiveData<State> getStateLiveData() {
        return sStateLiveData;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mWakeLock = new FtpServerWakeLock(this);

        sInstance = this;

        submitStart();
    }

    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        sInstance = null;

        submitStop();
        mExecutorService.shutdown();
    }

    private void submitStart() {
        if (mState == State.STARTING || mState == State.RUNNING) {
            return;
        }
        mWakeLock.acquire();
        FtpServerServiceNotification.startForeground(this);
        setState(State.STARTING);
        mExecutorService.submit(this::doStart);
    }

    private void onStartError(@NonNull Exception exception) {
        setState(State.STOPPED);
        ToastUtils.show(exception.toString(), this);
        FtpServerServiceNotification.stopForeground(this);
        mWakeLock.release();
    }

    private void submitStop() {
        if (mState == State.STOPPING || mState == State.STOPPED) {
            return;
        }
        setState(State.STOPPING);
        mExecutorService.submit(this::doStop);
        FtpServerServiceNotification.stopForeground(this);
        mWakeLock.release();
    }

    private void setState(@NonNull State state) {
        mState = state;
        if (sInstance == this || sInstance == null) {
            sStateLiveData.setValue(state);
        }
    }

    @WorkerThread
    private void postState(@NonNull State state) {
        AppUtils.runOnUiThread(() -> setState(state));
    }

    @WorkerThread
    private void doStart() {
        if (mServer != null) {
            return;
        }
        String username;
        String password;
        if (Settings.FTP_SERVER_ANONYMOUS_LOGIN.getValue()) {
            username = USERNAME_ANONYMOUS;
            password = null;
        } else {
            username = Settings.FTP_SERVER_USERNAME.getValue();
            password = Settings.FTP_SERVER_PASSWORD.getValue();
        }
        int port = Settings.FTP_SERVER_PORT.getValue();
        Path homeDirectory = Settings.FTP_SERVER_HOME_DIRECTORY.getValue();
        boolean writable = Settings.FTP_SERVER_WRITABLE.getValue();
        mServer = new FtpServer(username, password, port, homeDirectory, writable);
        try {
            mServer.start();
            postState(State.RUNNING);
        } catch (FtpException e) {
            e.printStackTrace();
            mServer = null;
            AppUtils.runOnUiThread(() -> onStartError(e));
        }
    }

    @WorkerThread
    private void doStop() {
        if (mServer == null) {
            return;
        }
        mServer.stop();
        mServer = null;
        postState(State.STOPPED);
    }
}
