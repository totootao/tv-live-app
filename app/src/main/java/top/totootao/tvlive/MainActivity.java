package top.totootao.tvlive;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 电视直播效果：把每一部电视剧当作一个 24/7 轮播频道。
 * 播放位置由「当前时刻 - 1970-01-01 00:00:00」的毫秒数对该剧总时长取模得到，
 * 由此算出「第几集 / 第几分 / 第几秒」，并直接 seek 到该位置播放，形成直播感。
 */
public class MainActivity extends Activity {

    // durations.json 的地址（视频地址倒数第二列为电视剧名）
    private static final String DURATIONS_URL =
            "https://www.totootao.top/alist/d/3-90/mnt/nas/视频/durations.json";
    private static final String PREFS = "tvlive_prefs";
    private static final String KEY_LAST = "last_series";
    private static final String TAG = "TVLive";

    private ExoPlayer player;
    private PlayerView playerView;
    private RecyclerView listView;
    private TextView tvSeries;
    private TextView tvPosition;
    private TextView listHint;
    private SeriesAdapter adapter;

    private final List<Series> seriesList = new ArrayList<>();
    private Series currentSeries;
    private SharedPreferences prefs;
    private final Handler tick = new Handler(Looper.getMainLooper());
    private Runnable ticker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        playerView = findViewById(R.id.player_view);
        listView = findViewById(R.id.series_list);
        tvSeries = findViewById(R.id.tv_series);
        tvPosition = findViewById(R.id.tv_position);
        listHint = findViewById(R.id.list_hint);
        listView.setLayoutManager(new LinearLayoutManager(this));

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setPlayWhenReady(true);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                // 当前集播完，按实时时钟跳到「下一个直播位置」
                if (state == Player.STATE_ENDED && currentSeries != null) {
                    advanceLive();
                }
            }
        });

        loadData();
    }

    // ---------- 数据加载 ----------
    private void loadData() {
        new Thread(() -> {
            String json = null;
            try {
                json = fetch(encodeUrl(DURATIONS_URL));
            } catch (Exception e) {
                Log.w(TAG, "网络加载 durations.json 失败，尝试内置副本", e);
            }
            if (json == null) {
                try {
                    json = readStream(getAssets().open("durations.json"));
                } catch (Exception e) {
                    Log.e(TAG, "内置副本也加载失败", e);
                }
            }
            final String finalJson = json;
            runOnUiThread(() -> {
                if (finalJson == null) {
                    listHint.setText("加载失败，请检查网络");
                    return;
                }
                parseJson(finalJson);
                onDataReady();
            });
        }).start();
    }

    private void parseJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            Map<String, List<Episode>> map = new LinkedHashMap<>();
            Iterator<String> it = root.keys();
            while (it.hasNext()) {
                String url = it.next();
                long dur = root.getLong(url);
                String decoded = decode(url);
                String[] parts = decoded.split("/");
                if (parts.length < 2) continue;
                // 倒数第二列 = 电视剧名
                String name = parts[parts.length - 2];
                String file = parts[parts.length - 1];
                List<Episode> lst = map.get(name);
                if (lst == null) {
                    lst = new ArrayList<>();
                    map.put(name, lst);
                }
                lst.add(new Episode(file, url, dur));
            }
            // 每部剧按文件名的数字前缀排序
            for (List<Episode> eps : map.values()) {
                Collections.sort(eps, (a, b) -> compareEp(a.file, b.file));
            }
            for (Map.Entry<String, List<Episode>> e : map.entrySet()) {
                long total = 0;
                for (Episode ep : e.getValue()) total += ep.duration;
                seriesList.add(new Series(e.getKey(), e.getValue(), total));
            }
        } catch (Exception ex) {
            Log.e(TAG, "JSON 解析错误", ex);
        }
    }

    private void onDataReady() {
        if (seriesList.isEmpty()) {
            listHint.setText("无可用频道");
            return;
        }
        adapter = new SeriesAdapter(seriesList, this::selectSeries);
        listView.setAdapter(adapter);

        // 首次进入播放第一个；之后恢复上一次播放的电视剧
        String last = prefs.getString(KEY_LAST, null);
        int idx = 0;
        if (last != null) {
            for (int i = 0; i < seriesList.size(); i++) {
                if (seriesList.get(i).name.equals(last)) {
                    idx = i;
                    break;
                }
            }
        }
        selectSeries(seriesList.get(idx).name);

        // 每秒刷新「直播位置」显示
        ticker = new Runnable() {
            @Override
            public void run() {
                if (currentSeries != null) {
                    long[] lp = computeLive(currentSeries, System.currentTimeMillis());
                    updateOverlay((int) lp[0], lp[1]);
                }
                tick.postDelayed(this, 1000);
            }
        };
        tick.post(ticker);
    }

    // ---------- 播放控制 ----------
    private void selectSeries(String name) {
        Series s = null;
        for (Series x : seriesList) {
            if (x.name.equals(name)) {
                s = x;
                break;
            }
        }
        if (s == null) return;
        currentSeries = s;
        if (adapter != null) adapter.setSelected(name);
        long[] lp = computeLive(s, System.currentTimeMillis());
        playEpisode(s, (int) lp[0], lp[1]);
        prefs.edit().putString(KEY_LAST, s.name).apply();
    }

    private void playEpisode(Series s, int epIndex, long offsetMs) {
        if (epIndex < 0 || epIndex >= s.episodes.size()) epIndex = 0;
        Episode ep = s.episodes.get(epIndex);
        player.setMediaItem(MediaItem.fromUri(encodeUrl(ep.url)), offsetMs);
        player.prepare();
        player.play();
        updateOverlay(epIndex, offsetMs);
    }

    private void advanceLive() {
        long[] lp = computeLive(currentSeries, System.currentTimeMillis());
        playEpisode(currentSeries, (int) lp[0], lp[1]);
    }

    private void updateOverlay(int epIndex, long offsetMs) {
        if (currentSeries == null || epIndex < 0 || epIndex >= currentSeries.episodes.size()) return;
        long sec = offsetMs / 1000;
        long hh = sec / 3600;
        long mm = (sec % 3600) / 60;
        long ss = sec % 60;
        tvSeries.setText(currentSeries.name);
        tvPosition.setText(String.format(Locale.CHINA, "第 %d 集    %02d:%02d:%02d",
                epIndex + 1, hh, mm, ss));
    }

    // ---------- 直播位置计算 ----------
    private long[] computeLive(Series s, long nowMs) {
        long total = s.totalDuration;
        long pos = total > 0 ? (((nowMs % total) + total) % total) : 0;
        long acc = 0;
        for (int i = 0; i < s.episodes.size(); i++) {
            long d = s.episodes.get(i).duration;
            if (pos < acc + d || i == s.episodes.size() - 1) {
                return new long[]{i, pos - acc};
            }
            acc += d;
        }
        return new long[]{0, 0};
    }

    // ---------- 工具方法 ----------
    private int compareEp(String a, String b) {
        int na = leadingInt(a);
        int nb = leadingInt(b);
        if (na >= 0 && nb >= 0 && na != nb) return Integer.compare(na, nb);
        return a.compareTo(b);
    }

    private int leadingInt(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0) return -1;
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (Exception e) {
            return -1;
        }
    }

    private String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 仅对各路径段做 UTF-8 百分号编码，避免中文路径在 HTTP 请求中出错 */
    private String encodeUrl(String url) {
        try {
            URL u = new URL(url);
            String path = u.getPath();
            String[] segs = path.split("/");
            StringBuilder sb = new StringBuilder();
            for (String seg : segs) {
                if (seg.isEmpty()) continue;
                sb.append("/").append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
            }
            String q = u.getQuery();
            return u.getProtocol() + "://" + u.getAuthority() + sb + (q != null ? "?" + q : "");
        } catch (Exception e) {
            return url;
        }
    }

    private String fetch(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "TVLive/1.0");
        return readStream(c.getInputStream());
    }

    private String readStream(InputStream is) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append("\n");
        return sb.toString();
    }

    // ---------- 生命周期 ----------
    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) player.play();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        tick.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    // ---------- 数据模型 ----------
    static class Episode {
        final String file;
        final String url;
        final long duration;

        Episode(String file, String url, long duration) {
            this.file = file;
            this.url = url;
            this.duration = duration;
        }
    }

    static class Series {
        final String name;
        final List<Episode> episodes;
        final long totalDuration;

        Series(String name, List<Episode> episodes, long totalDuration) {
            this.name = name;
            this.episodes = episodes;
            this.totalDuration = totalDuration;
        }
    }

    // ---------- 频道列表适配器 ----------
    class SeriesAdapter extends RecyclerView.Adapter<SeriesAdapter.VH> {
        private final List<Series> data;
        private final OnSeriesSelected onClick;
        private String selected = "";

        SeriesAdapter(List<Series> data, OnSeriesSelected onClick) {
            this.data = data;
            this.onClick = onClick;
        }

        void setSelected(String n) {
            selected = n;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int v) {
            View view = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.series_item, p, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            Series s = data.get(i);
            h.name.setText(s.name);
            h.itemView.setSelected(s.name.equals(selected));
            h.itemView.setOnClickListener(v -> onClick.onSelect(s.name));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView name;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.tv_name);
            }
        }
    }

    interface OnSeriesSelected {
        void onSelect(String name);
    }
}
