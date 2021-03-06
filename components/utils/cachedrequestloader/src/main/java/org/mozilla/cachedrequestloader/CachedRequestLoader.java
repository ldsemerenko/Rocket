package org.mozilla.cachedrequestloader;

import android.content.Context;
import android.os.AsyncTask;
import android.support.annotation.VisibleForTesting;
import android.support.v4.util.Pair;
import android.text.TextUtils;
import android.util.Log;

import org.mozilla.fileutils.FileUtils;
import org.mozilla.httptask.SimpleLoadUrlTask;
import org.mozilla.threadutils.ThreadUtils;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutionException;

public class CachedRequestLoader {

    private Context context;
    private String subscriptionKey;
    private String subscriptionUrl;
    private String userAgent;
    private int socketTag;
    private ResponseData stringLiveData;
    private static final String TAG = "CachedRequestLoader";
    // Mainly for testing purposes.
    @VisibleForTesting
    private boolean delayCacheLoad = false;
    @VisibleForTesting
    private boolean delayNetworkLoad = false;

    public CachedRequestLoader(Context context, String subscriptionKey, String subscriptionUrl, String userAgent, int socketTag) {
        this.context = context;
        this.subscriptionKey = subscriptionKey;
        this.subscriptionUrl = subscriptionUrl;
        this.userAgent = userAgent;
        this.socketTag = socketTag;
    }

    @VisibleForTesting
    public CachedRequestLoader(Context context, String subscriptionKey, String subscriptionUrl, String userAgent, int socketTag, boolean delayCacheLoad, boolean delayNetworkLoad) {
        this(context, subscriptionKey, subscriptionUrl, userAgent, socketTag);
        this.delayCacheLoad = delayCacheLoad;
        this.delayNetworkLoad = delayNetworkLoad;
    }

    public ResponseData getStringLiveData() {
        if (stringLiveData == null) {
            stringLiveData = new ResponseData();
            loadFromCacheWrapped();
            loadFromRemoteWrapped();
        }
        return stringLiveData;
    }

    private void loadFromCache() {
        try {
            new FileUtils.ReadStringFromFileTask<>(new FileUtils.GetCache(new WeakReference<>(context)).get(), subscriptionKey, stringLiveData, CachedRequestLoader::convertToPair).executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
            Log.e(TAG, "Failed to open Cache directory when reading cached banner config");
        }
    }

    private void loadFromCacheWrapped() {
        Inject.postDelayIfTesting(this::loadFromCache, delayCacheLoad);
    }

    private void loadFromRemote() {
        new RemoteLoadUrlTask(stringLiveData, this).executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, subscriptionUrl, userAgent, Integer.toString(socketTag));
    }

    private void loadFromRemoteWrapped() {
        Inject.postDelayIfTesting(this::loadFromRemote, delayNetworkLoad);
    }

    private static Pair<Integer, String> convertToPair(String input) {
        return new Pair<>(ResponseData.SOURCE_CACHE, input);
    }

    private void writeToCache(String string) {
        try {
            final Runnable runnable = new FileUtils.WriteStringToFileRunnable(new File(new FileUtils.GetCache(new WeakReference<>(context)).get(), subscriptionKey), string);
            ThreadUtils.postToBackgroundThread(runnable);
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
            Log.e(TAG, "Failed to open cache directory when writing to cache.");
        }
    }

    private void deleteCache() {
        try {
            final Runnable runnable = new FileUtils.DeleteFileRunnable(new File(new FileUtils.GetCache(new WeakReference<>(context)).get(), subscriptionKey));
            ThreadUtils.postToBackgroundThread(runnable);
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
            Log.e(TAG, "Failed to open cache directory when deleting cache.");
        }
    }

    private static class RemoteLoadUrlTask extends SimpleLoadUrlTask {

        private ResponseData liveData;
        private WeakReference<CachedRequestLoader> urlSubscriptionWeakReference;

        private RemoteLoadUrlTask(ResponseData liveData, CachedRequestLoader cachedRequestLoader) {
            this.liveData = liveData;
            urlSubscriptionWeakReference = new WeakReference<>(cachedRequestLoader);
        }


        @Override
        protected void onPostExecute(String line) {
            line = line.replaceAll("\n", "");
            this.liveData.setValue(new Pair<>(ResponseData.SOURCE_NETWORK, line) );
            CachedRequestLoader cachedRequestLoader = urlSubscriptionWeakReference.get();
            if (cachedRequestLoader == null) {
                return;
            }
            if (TextUtils.isEmpty(line)) {
                cachedRequestLoader.deleteCache();
            } else {
                cachedRequestLoader.writeToCache(line);
            }
        }
    }
}
