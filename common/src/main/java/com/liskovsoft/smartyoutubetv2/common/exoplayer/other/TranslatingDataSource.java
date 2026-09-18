package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.net.Uri;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.sharedutils.querystringparser.UrlQueryString;
import com.liskovsoft.sharedutils.querystringparser.UrlQueryStringFactory;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Passes video/audio/regular-subtitle requests straight through to a real {@link DataSource}.
 * For a caption url carrying YouTube's own "&amp;tlang=xx" marker, fetches the ORIGINAL
 * (untranslated) captions instead and translates them ourselves via translate-pa.googleapis.com
 * -- the same engine/endpoint used for Persiana's live Persian subtitles (asrlive.py) -- because
 * YouTube's own auto-translate stacks two lossy machine steps (ASR + its own EN->fa translate)
 * and reads noticeably worse.
 */
public class TranslatingDataSource implements DataSource {
    private static final String TAG = TranslatingDataSource.class.getSimpleName();
    private static final String TRANSLATE_URL = "https://translate-pa.googleapis.com/v1/translateHtml";
    private static final String TRANSLATE_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520";

    private final DataSource.Factory mUpstreamFactory;
    private DataSource mDelegate; // passthrough mode (video/audio/untranslated subs)
    private byte[] mBuffer;       // translated-subtitle mode
    private int mBufferPos;
    private Uri mUri;

    public TranslatingDataSource(DataSource.Factory upstreamFactory) {
        mUpstreamFactory = upstreamFactory;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        mUri = dataSpec.uri;

        UrlQueryString query = UrlQueryStringFactory.parse(dataSpec.uri);
        String targetLang = query != null && query.contains("tlang") ? query.get("tlang") : null;

        if (targetLang == null) {
            mDelegate = mUpstreamFactory.createDataSource();
            return mDelegate.open(dataSpec);
        }

        String sourceLang = query.contains("lang") ? query.get("lang") : "en";
        query.remove("tlang");
        String originalUrl = query.toString();

        mBuffer = fetchTranslatedVtt(originalUrl, sourceLang, targetLang);
        mBufferPos = 0;
        return mBuffer.length;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        if (mBuffer != null) {
            if (mBufferPos >= mBuffer.length) {
                return C.RESULT_END_OF_INPUT;
            }

            int toRead = Math.min(readLength, mBuffer.length - mBufferPos);
            System.arraycopy(mBuffer, mBufferPos, buffer, offset, toRead);
            mBufferPos += toRead;
            return toRead;
        }

        return mDelegate.read(buffer, offset, readLength);
    }

    @Override
    public Uri getUri() {
        return mUri;
    }

    @Override
    public void close() throws IOException {
        if (mDelegate != null) {
            mDelegate.close();
            mDelegate = null;
        }
        mBuffer = null;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        // ponytail: app-wide bandwidth meter is disabled (ExoMediaSourceFactory#USE_BANDWIDTH_METER),
        // so this listener is a no-op in practice; only wire it to the real passthrough source.
        if (mDelegate != null) {
            mDelegate.addTransferListener(transferListener);
        }
    }

    private byte[] fetchTranslatedVtt(String originalCaptionsUrl, String sourceLang, String targetLang) throws IOException {
        Response response = OkHttpManager.instance().doGetRequest(originalCaptionsUrl);

        if (response.body() == null) {
            throw new IOException("Empty captions response: " + originalCaptionsUrl);
        }

        List<long[]> timings = new ArrayList<>(); // {startMs, endMs}
        List<String> lines = parseJson3(response.body().string(), timings);

        List<String> translated = lines.isEmpty() ? lines : translateBatch(lines, sourceLang, targetLang);

        return buildVtt(timings, translated).getBytes(StandardCharsets.UTF_8);
    }

    /** YouTube's `fmt=json3` caption format: {"events":[{"tStartMs":.,"dDurationMs":.,"segs":[{"utf8":"..."}]}]} */
    private List<String> parseJson3(String json, List<long[]> timingsOut) throws IOException {
        List<String> lines = new ArrayList<>();

        try {
            JSONArray events = new JSONObject(json).optJSONArray("events");
            if (events == null) {
                return lines;
            }

            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                JSONArray segs = event.optJSONArray("segs");
                long durationMs = event.optLong("dDurationMs", -1);

                if (segs == null || durationMs <= 0) {
                    continue; // position-only/append events carry no renderable duration
                }

                StringBuilder text = new StringBuilder();
                for (int j = 0; j < segs.length(); j++) {
                    text.append(segs.getJSONObject(j).optString("utf8", ""));
                }

                String line = text.toString().replace('\n', ' ').trim();
                if (line.isEmpty()) {
                    continue;
                }

                long startMs = event.optLong("tStartMs", 0);
                lines.add(line);
                timingsOut.add(new long[] {startMs, startMs + durationMs});
            }
        } catch (Exception e) {
            throw new IOException("Malformed json3 captions", e);
        }

        return lines;
    }

    /** Same translate-pa.googleapis.com call as Persiana's asrlive.py `translate()`. */
    private List<String> translateBatch(List<String> lines, String sourceLang, String targetLang) throws IOException {
        JSONArray escaped = new JSONArray();
        for (String line : lines) {
            escaped.put(escapeHtml(line));
        }

        JSONArray request = new JSONArray()
                .put(new JSONArray().put(escaped).put(sourceLang).put(targetLang))
                .put("te");

        Response response = OkHttpManager.instance().doPostRequest(
                TRANSLATE_URL,
                java.util.Collections.singletonMap("X-Goog-Api-Key", TRANSLATE_KEY),
                request.toString(),
                "application/json+protobuf");

        if (response.body() == null) {
            throw new IOException("Empty translate response");
        }

        try {
            JSONArray out = new JSONArray(response.body().string()).getJSONArray(0);
            List<String> result = new ArrayList<>();
            for (int i = 0; i < out.length(); i++) {
                result.add(unescapeHtml(out.getString(i)));
            }

            if (result.size() != lines.size()) {
                Log.e(TAG, "Translate count mismatch, falling back to original text");
                return lines;
            }

            return result;
        } catch (Exception e) {
            Log.e(TAG, "Translate failed, falling back to original text: " + e.getMessage());
            return lines;
        }
    }

    private String buildVtt(List<long[]> timings, List<String> lines) {
        StringBuilder vtt = new StringBuilder("WEBVTT\n\n");

        for (int i = 0; i < lines.size(); i++) {
            long[] timing = timings.get(i);
            vtt.append(formatTimestamp(timing[0])).append(" --> ").append(formatTimestamp(timing[1])).append('\n');
            vtt.append(lines.get(i)).append("\n\n");
        }

        return vtt.toString();
    }

    private static String formatTimestamp(long ms) {
        long h = ms / 3600000;
        long m = (ms % 3600000) / 60000;
        long s = (ms % 60000) / 1000;
        long millis = ms % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, millis);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String unescapeHtml(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }
}
