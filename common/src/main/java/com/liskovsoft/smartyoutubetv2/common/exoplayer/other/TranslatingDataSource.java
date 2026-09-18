package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.net.Uri;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.liskovsoft.sharedutils.querystringparser.UrlQueryString;
import com.liskovsoft.sharedutils.querystringparser.UrlQueryStringFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Passes video/audio/regular-subtitle requests straight through to a real {@link DataSource}.
 * For a caption url carrying YouTube's own "&amp;tlang=xx" marker, redirects the fetch to our
 * own server (subsync/asrlive.py's "/livesubs/subtitle" route on the tv-pair VPS) instead of
 * trusting YouTube's own auto-translate -- which stacks two lossy machine steps (ASR + its own
 * EN->target translate) and reads noticeably worse than translating the original captions
 * ourselves via translate-pa.googleapis.com. The server does the fetch+translate+VTT-build (same
 * engine already used for Persiana's live Persian subtitles) and returns finished VTT bytes, so
 * this class only ever rewrites a Uri -- and so that the TV's own IP never calls YouTube's
 * timedtext endpoint or translate-pa directly (that got this TV's IP rate-limited during testing).
 */
public class TranslatingDataSource implements DataSource {
    private static final String SUBTITLE_PROXY = "http://147.224.130.108/livesubs/subtitle";

    private final DataSource.Factory mUpstreamFactory;
    private final java.util.List<TransferListener> mPendingListeners = new java.util.ArrayList<>();
    private DataSource mDelegate;

    public TranslatingDataSource(DataSource.Factory upstreamFactory) {
        mUpstreamFactory = upstreamFactory;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        // addTransferListener() is called by ExoPlayer right after the factory creates this
        // instance, before open() -- mDelegate doesn't exist yet at that point, so listeners
        // queue here and get attached once it does, instead of silently being dropped (that
        // broke ExoPlayer's transfer/bandwidth tracking for every request through this class).
        mDelegate = mUpstreamFactory.createDataSource();
        for (TransferListener listener : mPendingListeners) {
            mDelegate.addTransferListener(listener);
        }
        return mDelegate.open(redirectIfTranslated(dataSpec));
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        return mDelegate.read(buffer, offset, readLength);
    }

    @Override
    public Uri getUri() {
        return mDelegate != null ? mDelegate.getUri() : null;
    }

    @Override
    public void close() throws IOException {
        if (mDelegate != null) {
            mDelegate.close();
        }
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        if (mDelegate != null) {
            mDelegate.addTransferListener(transferListener);
        } else {
            mPendingListeners.add(transferListener);
        }
    }

    private DataSpec redirectIfTranslated(DataSpec dataSpec) {
        UrlQueryString query = UrlQueryStringFactory.parse(dataSpec.uri);
        String targetLang = query != null && query.contains("tlang") ? query.get("tlang") : null;

        if (targetLang == null) {
            return dataSpec;
        }

        String sourceLang = query.contains("lang") ? query.get("lang") : "en";
        query.remove("tlang");
        query.set("fmt", "json3"); // clean per-line events; the app's own "vtt" format is unusable here
        String originalUrl = query.toString();

        String proxyUrl = SUBTITLE_PROXY
                + "?url=" + encode(originalUrl)
                + "&sl=" + encode(sourceLang)
                + "&tl=" + encode(targetLang);

        return dataSpec.withUri(Uri.parse(proxyUrl));
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e); // UTF-8 is always supported
        }
    }
}
