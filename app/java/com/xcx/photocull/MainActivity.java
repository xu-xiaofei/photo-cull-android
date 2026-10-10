package com.xcx.photocull;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 照片选片：主页平铺相册 → 点相册 → 全屏卡片滑动（左不喜欢/右喜欢）
 * → 全部划完自动把不喜欢的送系统回收站；中途退出询问保留或放弃。
 * 底部胶片预览条可跳转；喜欢/不喜欢在缩略图与主图右上角标红心/灰心。
 * 性能：缩略图每张只解码一次（缓存）；滑动仅原地更新标记；大图解码带代际守卫。
 */
public class MainActivity extends Activity {

    private static final String[] EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"};
    private static final int SCREEN_ALBUMS = 1, SCREEN_CULL = 2;
    private static final int REQ_TRASH = 41;

    private FrameLayout root;
    private View viewAlbums, viewCull;
    private LinearLayout albumListContainer;
    private ImageView frontCard, backCard;
    private TextView tvProgress, tvHeart;
    private View btnUndo, btnRetryTrash;
    private LinearLayout filmstrip;
    private HorizontalScrollView filmstripScroll;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbIo = Executors.newSingleThreadExecutor();
    /** 缩略图 LRU 缓存（上限 512 张；淘汰不 recycle，交给 GC，避免显示中位图被回收崩溃）。 */
    private final Map<String, Bitmap> thumbCache = new LinkedHashMap<String, Bitmap>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 512;
        }
    };
    private final List<ImageView> stripThumbs = new ArrayList<>();
    private final List<TextView> stripBadges = new ArrayList<>();
    private int decodeGen = 0;

    private String currentBucketId;
    private final List<File> all = new ArrayList<>();
    private final List<String> allUri = new ArrayList<>();
    private final LinkedHashMap<String, Boolean> decisions = new LinkedHashMap<>();
    private final Object lock = new Object();
    private int cur = -1;
    private volatile Bitmap bmpFront, bmpBack;
    private String bmpBackPath;
    private boolean applying = false;
    private boolean finished = false;
    private boolean trashPending = false;
    private float downX, downY;
    private long downAt;
    private boolean dragging;

    static class Album {
        String bucketId, name, cover; int count;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(android.R.style.Theme_Material_NoActionBar);
        buildUi();
        if (!hasAllFilesAccess()) requestAllFilesAccess();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasAllFilesAccess() && screen == SCREEN_ALBUMS && albumListContainer.getChildCount() == 0) {
            loadAlbums();
        }
    }

    private boolean hasAllFilesAccess() {
        return Environment.isExternalStorageManager();
    }

    private void requestAllFilesAccess() {
        toast("请授予「所有文件访问」权限后返回");
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
            catch (Exception ignored) { }
        }
    }

    // ================= UI =================

    private int dp(float v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    private View pillButton(String text, String colorHex, float sizeSp) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(sizeSp);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(colorHex));
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        return b;
    }

    private int screen = SCREEN_ALBUMS;

    private void showScreen(int s) {
        screen = s;
        viewAlbums.setVisibility(s == SCREEN_ALBUMS ? View.VISIBLE : View.GONE);
        viewCull.setVisibility(s == SCREEN_CULL ? View.VISIBLE : View.GONE);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 相册列表（主页） ----
        viewAlbums = new LinearLayout(this);
        ((LinearLayout) viewAlbums).setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView ht = new TextView(this);
        ht.setText("相册（点一个开始选片）");
        ht.setTextColor(Color.WHITE);
        ht.setTextSize(18);
        head.addView(ht);
        TextView sp = new TextView(this);
        head.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        TextView refresh = new TextView(this);
        refresh.setText("刷新");
        refresh.setTextColor(0xFF4FC3F7);
        refresh.setTextSize(15);
        refresh.setPadding(dp(12), dp(4), dp(4), dp(4));
        refresh.setOnClickListener(v -> loadAlbums());
        head.addView(refresh);
        ((LinearLayout) viewAlbums).addView(head);
        albumListContainer = new LinearLayout(this);
        albumListContainer.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(albumListContainer);
        ((LinearLayout) viewAlbums).addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(viewAlbums, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 选片 ----
        viewCull = new FrameLayout(this);
        ((FrameLayout) viewCull).setBackgroundColor(Color.BLACK);

        backCard = new ImageView(this);
        backCard.setScaleType(ImageView.ScaleType.FIT_CENTER);
        backCard.setScaleX(0.94f); backCard.setScaleY(0.94f);
        ((FrameLayout) viewCull).addView(backCard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        frontCard = new ImageView(this);
        frontCard.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frontCard.setBackgroundColor(Color.BLACK);
        ((FrameLayout) viewCull).addView(frontCard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tvHeart = new TextView(this);
        tvHeart.setTextSize(34);
        FrameLayout.LayoutParams hLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        hLp.topMargin = dp(10); hLp.rightMargin = dp(14);
        ((FrameLayout) viewCull).addView(tvHeart, hLp);
        tvHeart.setVisibility(View.GONE);

        tvProgress = new TextView(this);
        tvProgress.setTextColor(Color.WHITE);
        tvProgress.setTextSize(13);
        tvProgress.setBackgroundColor(0x55000000);
        tvProgress.setPadding(dp(10), dp(4), dp(10), dp(4));
        FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        pLp.topMargin = dp(6);
        ((FrameLayout) viewCull).addView(tvProgress, pLp);

        btnUndo = pillButton("↩", "#88000000", 14);
        btnUndo.setOnClickListener(v -> undo());
        FrameLayout.LayoutParams uLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        uLp.topMargin = dp(6); uLp.leftMargin = dp(6);
        ((FrameLayout) viewCull).addView(btnUndo, uLp);

        btnRetryTrash = pillButton("🗑 送回收站", "#88000000", 13);
        FrameLayout.LayoutParams rLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        rLp.bottomMargin = dp(96); rLp.rightMargin = dp(10);
        ((FrameLayout) viewCull).addView(btnRetryTrash, rLp);
        btnRetryTrash.setVisibility(View.GONE);
        btnRetryTrash.setOnClickListener(v -> finishAndTrash());

        filmstripScroll = new HorizontalScrollView(this);
        filmstripScroll.setHorizontalScrollBarEnabled(false);
        filmstripScroll.setBackgroundColor(0x88000000);
        filmstrip = new LinearLayout(this);
        filmstrip.setOrientation(LinearLayout.HORIZONTAL);
        filmstrip.setPadding(dp(6), dp(6), dp(6), dp(6));
        filmstripScroll.addView(filmstrip);
        FrameLayout.LayoutParams fsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(92), Gravity.BOTTOM);
        ((FrameLayout) viewCull).addView(filmstripScroll, fsLp);

        frontCard.setOnTouchListener(this::onTouch);
        root.addView(viewCull, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        viewCull.setVisibility(View.GONE);
    }

    // ================= 相册列表 =================

    private void loadAlbums() {
        albumListContainer.removeAllViews();
        stripThumbs.clear();
        stripBadges.clear();
        TextView loading = new TextView(this);
        loading.setText("正在读取相册…");
        loading.setTextColor(0xFF9E9E9E);
        loading.setTextSize(16);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, dp(40), 0, 0);
        albumListContainer.addView(loading);
        io.execute(() -> {
            final List<Album> albums = queryAlbums();
            runOnUiThread(() -> {
                albumListContainer.removeAllViews();
                if (albums.isEmpty()) {
                    TextView empty = new TextView(this);
                    empty.setText("没有找到照片。请先把照片传入平板/手机。");
                    empty.setTextColor(0xFF9E9E9E);
                    empty.setTextSize(15);
                    empty.setPadding(dp(16), dp(40), dp(16), 0);
                    albumListContainer.addView(empty);
                    return;
                }
                for (final Album a : albums) albumListContainer.addView(albumRow(a));
            });
        });
    }

    private List<Album> queryAlbums() {
        Map<String, Album> map = new LinkedHashMap<>();
        Cursor c = null;
        try {
            String[] proj = {
                    MediaStore.Images.Media.BUCKET_ID,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.DATA};
            c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    proj, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (c != null) {
                int iId = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID);
                int iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                int iData = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                while (c.moveToNext()) {
                    String bid = c.getString(iId);
                    String name = c.getString(iName);
                    String data = c.getString(iData);
                    if (bid == null || name == null || data == null) continue;
                    Album a = map.get(bid);
                    if (a == null) {
                        a = new Album();
                        a.bucketId = bid;
                        a.name = name;
                        a.cover = data;
                        map.put(bid, a);
                    }
                    a.count++;
                }
            }
        } catch (Exception e) {
            log("相册查询失败: " + e);
        } finally {
            if (c != null) c.close();
        }
        List<Album> out = new ArrayList<>(map.values());
        java.util.Collections.sort(out, (x, y) -> Integer.compare(y.count, x.count));
        return out;
    }

    private View albumRow(final Album a) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(14), dp(8));
        row.setOnClickListener(v -> openAlbum(a));

        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable ph = new GradientDrawable();
        ph.setColor(0xFF212121);
        ph.setCornerRadius(dp(8));
        cover.setBackground(ph);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(96), dp(72));
        row.addView(cover, clp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.setMarginStart(dp(14));
        TextView name = new TextView(this);
        name.setText(a.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(17);
        name.setSingleLine(true);
        texts.addView(name);
        TextView cnt = new TextView(this);
        cnt.setText(a.count + " 张");
        cnt.setTextColor(0xFF9E9E9E);
        cnt.setTextSize(13);
        cnt.setPadding(0, dp(3), 0, 0);
        texts.addView(cnt);
        row.addView(texts, tlp);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(0xFF757575);
        arrow.setTextSize(24);
        row.addView(arrow);

        final String coverPath = a.cover;
        io.execute(() -> {
            Bitmap b = decodeScaled(coverPath, dp(96), dp(72));
            if (b != null) runOnUiThread(() -> cover.setImageBitmap(b));
        });
        return row;
    }

    // ================= 打开相册 =================

    private void openAlbum(final Album a) {
        currentBucketId = a.bucketId;
        io.execute(() -> {
            List<File> files = new ArrayList<>();
            List<String> uris = new ArrayList<>();
            Cursor c = null;
            try {
                String[] proj = {MediaStore.Images.Media.DATA, MediaStore.Images.Media._ID};
                c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        proj, MediaStore.Images.Media.BUCKET_ID + "=?", new String[]{a.bucketId},
                        MediaStore.Images.Media.DATE_ADDED + " DESC");
                if (c != null) {
                    int iData = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                    int iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (c.moveToNext()) {
                        String p = c.getString(iData);
                        if (p == null || !new File(p).exists()) continue;
                        files.add(new File(p));
                        uris.add(Uri.parse(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()
                                + "/" + c.getLong(iId)).toString());
                    }
                }
            } catch (Exception e) {
                log("照片列表查询失败: " + e);
            } finally {
                if (c != null) c.close();
            }
            final List<File> ff = files;
            final List<String> uu = uris;
            runOnUiThread(() -> {
                all.clear();
                all.addAll(ff);
                allUri.clear();
                allUri.addAll(uu);
                synchronized (lock) { decisions.clear(); }
                synchronized (thumbCache) { thumbCache.clear(); }
                cur = -1;
                finished = false;
                trashPending = false;
                btnRetryTrash.setVisibility(View.GONE);
                restoreState();
                showScreen(SCREEN_CULL);
                buildFilmstrip();
                showCard();
                preCacheThumbs();
            });
        });
    }

    // ================= 状态 =================

    private File stateFile() {
        return new File(getExternalFilesDir(null), "state.json");
    }

    private void persistState() {
        io.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("bucket", currentBucketId == null ? "" : currentBucketId);
                o.put("finished", finished);
                o.put("trashPending", trashPending);
                JSONArray arr = new JSONArray();
                synchronized (lock) {
                    for (Map.Entry<String, Boolean> e : decisions.entrySet()) {
                        JSONObject j = new JSONObject();
                        j.put("p", e.getKey());
                        j.put("l", e.getValue());
                        arr.put(j);
                    }
                }
                o.put("d", arr);
                try (OutputStream os = new FileOutputStream(stateFile())) {
                    os.write(o.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                log("状态保存失败: " + e);
            }
        });
    }

    private void restoreState() {
        try {
            File f = stateFile();
            if (!f.exists()) return;
            JSONObject o = new JSONObject(new String(
                    java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (!currentBucketId.equals(o.optString("bucket", ""))) return;
            boolean wasFinished = o.optBoolean("finished", false);
            // finished 不再作为持久化锁：恢复历史决策仅供查看/改判，会话始终可滑动
            finished = false;
            trashPending = o.optBoolean("trashPending", false);
            JSONArray arr = o.optJSONArray("d");
            if (arr != null) {
                synchronized (lock) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject e = arr.getJSONObject(i);
                        String p = e.getString("p");
                        for (File x : all) {
                            if (x.getAbsolutePath().equals(p)) {
                                decisions.put(p, e.getBoolean("l"));
                                break;
                            }
                        }
                    }
                }
                if (trashPending) btnRetryTrash.setVisibility(View.VISIBLE);
                if (wasFinished) toast("上次已选完并送过回收站；可继续滑动改判");
            }
        } catch (Exception e) {
            log("状态恢复失败: " + e);
        }
    }

    private int frontIndex() {
        if (cur >= 0) return cur;
        for (int i = 0; i < all.size(); i++) {
            if (!decisions.containsKey(all.get(i).getAbsolutePath())) return i;
        }
        return all.isEmpty() ? -1 : all.size() - 1;
    }

    private int likedCount() {
        int n = 0;
        for (Boolean l : decisions.values()) if (l) n++;
        return n;
    }

    private void refreshProgress() {
        int decided = decisions.size();
        int remain = all.size() - decided;
        if (remain == 0 && !all.isEmpty()) {
            tvProgress.setText(String.format(Locale.US, "共 %d 张已划完   \u2665\uFE0E %d",
                    all.size(), likedCount()));
        } else {
            tvProgress.setText(String.format(Locale.US, "剩 %d / 共 %d   \u2665\uFE0E %d",
                    remain, all.size(), likedCount()));
        }
    }

    // ================= 卡片显示（缩略图秒开 + 大图代际守卫） =================

    private void showCard() {
        int idx = frontIndex();
        if (idx < 0) {
            frontCard.setImageDrawable(null);
            backCard.setImageDrawable(null);
            bmpFront = bmpBack = null;
            refreshHeart(null);
            refreshProgress();
            updateFilmstripState();
            return;
        }
        File f = all.get(idx);
        currentIndexHolder = idx;
        final int gen = ++decodeGen;
        bmpFront = null; bmpBack = null;
        resetCard(frontCard, 1f);
        resetCard(backCard, 1f);
        Bitmap ft = thumbFor(f);
        if (ft != null) frontCard.setImageBitmap(ft);
        File back = idx + 1 < all.size() ? all.get(idx + 1) : null;
        Bitmap bt;
        synchronized (thumbCache) { bt = back != null ? thumbCache.get(back.getAbsolutePath()) : null; }
        if (bt != null) backCard.setImageBitmap(bt);
        refreshHeart(decisions.get(f.getAbsolutePath()));
        refreshProgress();
        updateFilmstripState();
        io.execute(() -> {
            Bitmap b0 = decode(f);
            runOnUiThread(() -> {
                if (gen != decodeGen) return;
                bmpFront = b0;
                if (b0 != null) frontCard.setImageBitmap(b0);
            });
            final File backF = back;
            Bitmap b2 = backF != null ? decode(backF) : null;
            runOnUiThread(() -> {
                if (gen != decodeGen) return;
                bmpBack = b2;
                bmpBackPath = backF != null ? backF.getAbsolutePath() : null;
                if (b2 != null) backCard.setImageBitmap(b2);
            });
        });
    }

    private int currentIndexHolder = -1;

    private void refreshHeart(Boolean liked) {
        if (liked == null) {
            tvHeart.setVisibility(View.GONE);
            return;
        }
        tvHeart.setVisibility(View.VISIBLE);
        tvHeart.setText("\u2665\uFE0E");
        tvHeart.setTextColor(liked ? 0xFFE53935 : 0xFFBDBDBD);
    }

    private void resetCard(View card, float scale) {
        card.setTranslationX(0);
        card.setTranslationY(0);
        card.setRotation(0);
        card.setAlpha(1f);
        card.setScaleX(scale);
        card.setScaleY(scale);
    }

    private float swipeThreshold() {
        return Math.max(dp(48), Math.min(getWidth() * 0.12f, dp(120)));
    }

    private Bitmap decode(File f) {
        return decodeScaled(f.getAbsolutePath(),
                Math.max(1080, getResources().getDisplayMetrics().widthPixels),
                Math.max(1920, getResources().getDisplayMetrics().heightPixels));
    }

    private Bitmap decodeScaled(String path, int targetW, int targetH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (o.outWidth / sample / 2 >= targetW || o.outHeight / sample / 2 >= targetH) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(path, o2);
            if (b == null) return null;
            ExifInterface ex = new ExifInterface(path);
            int rot = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            int deg = 0;
            if (rot == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
            else if (rot == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            else if (rot == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
            if (deg != 0) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            }
            return b;
        } catch (Throwable t) {
            log("解码失败 " + path + ": " + t);
            return null;
        }
    }

    /** 缩略图取用：缓存命中直接返回；未命中同步解一张 64dp 小图（毫秒级），绝不因未预热而黑屏。 */
    private Bitmap thumbFor(File f) {
        String p = f.getAbsolutePath();
        Bitmap b;
        synchronized (thumbCache) { b = thumbCache.get(p); }
        if (b != null) return b;
        b = decodeScaled(p, dp(64), dp(72));
        if (b != null) {
            synchronized (thumbCache) { thumbCache.put(p, b); }
        }
        return b;
    }

    // ================= 滑动（记账同步，动画只做视觉） =================

    private boolean onTouch(View v, MotionEvent ev) {
        if (applying || screen != SCREEN_CULL || frontIndex() < 0 || finished) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX(); downY = ev.getRawY(); downAt = System.currentTimeMillis();
                dragging = true;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return true;
                float dx = ev.getRawX() - downX;
                float dy = ev.getRawY() - downY;
                frontCard.setTranslationX(dx);
                frontCard.setTranslationY(dy * 0.3f);
                float capped = Math.max(-dp(56), Math.min(dp(56), dx / 14f));
                frontCard.setRotation(capped);
                float p = Math.min(1f, Math.abs(dx) / swipeThreshold());
                float s = 0.94f + 0.06f * p;
                backCard.setScaleX(s);
                backCard.setScaleY(s);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) return true;
                dragging = false;
                float dx = ev.getRawX() - downX;
                float dy = ev.getRawY() - downY;
                float dt = Math.max(1, System.currentTimeMillis() - downAt) / 1000f;
                float vx = dx / dt;
                float threshold = swipeThreshold();
                Boolean liked = null;
                if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy)) liked = dx > 0;
                else if (Math.abs(dx) > dp(40) && Math.abs(vx) > dp(1100)
                        && Math.abs(dx) > Math.abs(dy)) liked = dx > 0;
                if (liked == null) {
                    frontCard.animate().translationX(0).translationY(0).rotation(0).alpha(1f).setDuration(160).start();
                    backCard.animate().scaleX(0.94f).scaleY(0.94f).setDuration(160).start();
                    return true;
                }
                // 同步记账（零竞态）
                int fi = frontIndex();
                if (fi < 0) return true;
                File decidedFile = all.get(fi);
                log("decide: " + decidedFile.getName() + " -> " + (liked ? "LIKE" : "PASS")
                        + " (decided=" + (decisions.size() + 1) + "/" + all.size() + ")");
                record(decidedFile, liked);
                // 飞出残影动画（视觉）：快照当前卡到临时层飞出，front 由 advance 原地顶替
                final ImageView ghost = new ImageView(this);
                ghost.setImageDrawable(frontCard.getDrawable());
                ghost.setScaleType(ImageView.ScaleType.FIT_CENTER);
                ghost.setLayoutParams(frontCard.getLayoutParams());
                ghost.setTranslationX(frontCard.getTranslationX());
                ghost.setTranslationY(frontCard.getTranslationY());
                ghost.setRotation(frontCard.getRotation());
                ghost.setAlpha(0.9f);
                root.addView(ghost);
                float dir = liked ? 1 : -1;
                ghost.animate().translationX(dir * getWidth() * 1.25f)
                        .translationY(dy * 0.5f).rotation(dir * 20).alpha(0.5f)
                        .setDuration(190).withEndAction(() -> root.removeView(ghost)).start();
                advance();
                return true;
            }
            default:
                return true;
        }
    }

    private float getWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    /** 同步记账。全部划完 → 应用内确认后送回收站。 */
    private void record(File f, boolean liked) {
        synchronized (lock) {
            decisions.put(f.getAbsolutePath(), liked);
        }
        persistState();
        toast(liked ? "♥ 喜欢" : "✗ 将删除");
        if (decisions.size() >= all.size()) {
            log("全部划完，弹确认后送回收站");
            confirmFinishAndTrash();
        }
    }

    /** 全部划完：先应用内确认（说明谁会被删），确认后才走系统授权弹窗。 */
    private void confirmFinishAndTrash() {
        new AlertDialog.Builder(this)
                .setTitle("全部划完")
                .setMessage(String.format(Locale.US,
                        "喜欢 %d 张，将原地保留；\n不喜欢 %d 张，将送入系统回收站（可在图库「最近删除」恢复）。",
                        likedCount(), decisions.size() - likedCount()))
                .setPositiveButton("送回收站", (d, w) -> finishAndTrash())
                .setNegativeButton("先不了", (d, w) -> {
                    trashPending = true;
                    persistState();
                    btnRetryTrash.setVisibility(View.VISIBLE);
                    toast("可随时点右下角「送回收站」");
                })
                .show();
    }

    /** 顶替/前进：front ← 下一张，back ← 再下一张（均先用缩略图占位）。 */
    private void advance() {
        int next = -1;
        int from = Math.max(0, cur);
        for (int i = from; i < all.size(); i++) {
            if (!decisions.containsKey(all.get(i).getAbsolutePath())) { next = i; break; }
        }
        if (next < 0) {
            for (int i = 0; i < all.size(); i++) {
                if (!decisions.containsKey(all.get(i).getAbsolutePath())) { next = i; break; }
            }
        }
        cur = next < 0 ? all.size() - 1 : next;
        File nextF = all.get(cur);
        final int gen = ++decodeGen;
        // back 就绪且正好是下一张 → 无缝顶替；否则用缩略图占位
        boolean promoted = bmpBack != null && nextF.getAbsolutePath().equals(bmpBackPath);
        if (promoted) {
            bmpFront = bmpBack;
            frontCard.setImageBitmap(bmpFront);
        } else {
            Bitmap t = thumbFor(nextF);
            if (t != null) frontCard.setImageBitmap(t);
        }
        resetCard(frontCard, 1f);
        bmpBack = null;
        backCard.setImageDrawable(null);
        backCard.setScaleX(0.94f); backCard.setScaleY(0.94f);
        refreshHeart(decisions.get(nextF.getAbsolutePath()));
        refreshProgress();
        updateFilmstripState();
        // 后台解码：front 大图（若为占位）+ 新 back
        final boolean needFrontFull = !promoted;
        final File frontF = nextF;
        final File backF = cur + 1 < all.size() ? all.get(cur + 1) : null;
        io.execute(() -> {
            if (needFrontFull) {
                Bitmap f0 = decode(frontF);
                runOnUiThread(() -> {
                    if (gen != decodeGen) return;
                    bmpFront = f0;
                    if (f0 != null) frontCard.setImageBitmap(f0);
                });
            }
            Bitmap b1 = backF != null ? decode(backF) : null;
            runOnUiThread(() -> {
                if (gen != decodeGen) return;
                bmpBack = b1;
                bmpBackPath = backF != null ? backF.getAbsolutePath() : null;
                if (b1 != null) backCard.setImageBitmap(b1);
                backCard.setScaleX(backF != null ? 0.94f : 1f);
                backCard.setScaleY(backF != null ? 0.94f : 1f);
            });
        });
    }

    private void undo() {
        if (finished) { toast("选片已结束"); return; }
        String last = null;
        synchronized (lock) {
            for (String k : decisions.keySet()) last = k;
        }
        if (last == null) { toast("没有可撤销的操作"); return; }
        Boolean liked;
        synchronized (lock) { liked = decisions.remove(last); }
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getAbsolutePath().equals(last)) { cur = i; break; }
        }
        persistState();
        toast("已撤销：" + (Boolean.TRUE.equals(liked) ? "♥ 喜欢" : "✗ 不喜欢"));
        showCard();
    }

    // ================= 结束 / 回收站 =================

    private void finishAndTrash() {
        if (applying || finished) return;
        List<Uri> toTrash = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, Boolean> e : decisions.entrySet()) {
                if (!e.getValue()) {
                    int i = indexOfPath(e.getKey());
                    if (i >= 0) toTrash.add(Uri.parse(allUri.get(i)));
                }
            }
        }
        if (toTrash.isEmpty()) {
            markFinished();
            toast("完成！本组没有需要删除的照片");
            return;
        }
        applying = true;
        tvProgress.setText("正在送回收站…");
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.app.PendingIntent pi = MediaStore.createTrashRequest(
                        getContentResolver(), toTrash, true);
                startIntentSenderForResult(pi.getIntentSender(), REQ_TRASH, null, 0, 0, 0);
            } catch (Exception e) {
                log("回收站请求失败: " + e);
                applying = false;
                trashFallback(toTrash);
            }
        } else {
            trashFallback(toTrash);
        }
    }

    private void trashFallback(List<Uri> toTrash) {
        int ok = 0;
        File parentDir0 = all.isEmpty() ? Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                : all.get(0).getParentFile();
        File target = new File(parentDir0, ".trash_选片");
        target.mkdirs();
        for (Uri u : toTrash) {
            String p = pathOfUri(u);
            if (p == null) continue;
            File src = new File(p);
            File dst = new File(target, src.getName());
            try {
                if (!src.renameTo(dst)) {
                    copyFile(src, dst);
                    src.delete();
                }
                MediaScannerConnection.scanFile(this,
                        new String[]{src.getAbsolutePath(), dst.getAbsolutePath()}, null, null);
                ok++;
            } catch (Exception e) {
                log("回收站兜底失败: " + e);
            }
        }
        applying = false;
        markFinished();
        toast("已移入 .trash_选片 文件夹（" + ok + " 张）");
    }

    private int indexOfPath(String p) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getAbsolutePath().equals(p)) return i;
        }
        return -1;
    }

    private String pathOfUri(Uri u) {
        for (int i = 0; i < allUri.size() && i < all.size(); i++) {
            if (allUri.get(i).equals(u.toString())) return all.get(i).getAbsolutePath();
        }
        return null;
    }

    private void markFinished() {
        finished = true;
        trashPending = false;
        btnRetryTrash.setVisibility(View.GONE);
        persistState();
        refreshProgress();
        updateFilmstripState();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TRASH) {
            applying = false;
            if (resultCode == RESULT_OK) {
                markFinished();
                toast("不喜欢的已送入系统回收站（可在图库最近删除中恢复）");
            } else {
                trashPending = true;
                persistState();
                btnRetryTrash.setVisibility(View.VISIBLE);
                toast("已取消。可点右下角「送回收站」重试");
            }
            refreshProgress();
            updateFilmstripState();
        }
    }

    // ================= 胶片预览条（只建一次，原地更新） =================

    private void buildFilmstrip() {
        filmstrip.removeAllViews();
        stripThumbs.clear();
        stripBadges.clear();
        lastStripCur = -1;
        for (int i = 0; i < all.size(); i++) {
            final int idx = i;
            final File f = all.get(i);

            FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(64), dp(72));
            clp.setMarginEnd(dp(4));
            cell.setLayoutParams(clp);

            ImageView th = new ImageView(this);
            th.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF212121);
            bg.setStroke(dp(1), 0xFF444444);
            bg.setCornerRadius(dp(6));
            th.setBackground(bg);
            cell.addView(th, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView badge = new TextView(this);
            badge.setText("\u2665\uFE0E");
            badge.setTextSize(15);
            badge.setVisibility(View.GONE);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            blp.topMargin = dp(2); blp.rightMargin = dp(4);
            cell.addView(badge, blp);

            cell.setOnClickListener(v -> {
                if (finished) {
                    refreshHeart(decisions.get(f.getAbsolutePath()));
                    toast("选片已结束（红=喜欢，灰=已进回收站）");
                    return;
                }
                cur = idx;
                showCard();
            });
            filmstrip.addView(cell);
            stripThumbs.add(th);
            stripBadges.add(badge);

            Bitmap cached;
            synchronized (thumbCache) { cached = thumbCache.get(f.getAbsolutePath()); }
            if (cached != null) {
                th.setImageBitmap(cached);
            }
            // 未命中的格子由 preCacheThumbs 统一按格回填，不再逐格排任务
        }
        updateFilmstripState();
    }

    private int lastStripCur = -1;

    /** 原地增量更新标记与白框（滑动时只更新变化的格子，不整条重绘）。 */
    private void updateFilmstripState() {
        int curIdx = frontIndex();
        int n = Math.min(stripBadges.size(), all.size());
        for (int i = 0; i < n; i++) {
            Boolean liked = decisions.get(all.get(i).getAbsolutePath());
            TextView badge = stripBadges.get(i);
            int vis = liked == null ? View.GONE : View.VISIBLE;
            if (badge.getVisibility() != vis) badge.setVisibility(vis);
            if (liked != null) {
                int color = Boolean.TRUE.equals(liked) ? 0xFFE53935 : 0xFFBDBDBD;
                if (badge.getCurrentTextColor() != color) badge.setTextColor(color);
            }
        }
        if (curIdx != lastStripCur) {
            if (lastStripCur >= 0 && lastStripCur < n) {
                GradientDrawable oldBg = (GradientDrawable) stripThumbs.get(lastStripCur).getBackground();
                oldBg.setStroke(dp(1), 0xFF444444);
            }
            if (curIdx >= 0 && curIdx < n) {
                GradientDrawable newBg = (GradientDrawable) stripThumbs.get(curIdx).getBackground();
                newBg.setStroke(dp(2), Color.WHITE);
            }
            lastStripCur = curIdx;
            filmstripScroll.post(() -> {
                if (curIdx >= 0 && curIdx < stripThumbs.size()) {
                    int x = Math.max(0, curIdx * dp(68) - (int) getWidth() / 2);
                    filmstripScroll.smoothScrollTo(x, 0);
                }
            });
        }
    }

    /** 后台生成整卷缩略图缓存；命中缓存的格子也回填一次（修复先行解码导致的黑格）。 */
    private void preCacheThumbs() {
        thumbIo.execute(() -> {
            final List<File> snap = new ArrayList<>(all);
            for (int k = 0; k < snap.size(); k++) {
                final File f = snap.get(k);
                final int idx = k;
                Bitmap b;
                synchronized (thumbCache) { b = thumbCache.get(f.getAbsolutePath()); }
                if (b == null) {
                    b = decodeScaled(f.getAbsolutePath(), dp(64), dp(72));
                    if (b != null) {
                        synchronized (thumbCache) { thumbCache.put(f.getAbsolutePath(), b); }
                    }
                }
                if (b != null) {
                    final Bitmap fb = b;
                    runOnUiThread(() -> {
                        // 校验快照下标仍指向同一文件（防快速切换相册后错位回填）
                        if (idx < stripThumbs.size() && idx < all.size()
                                && all.get(idx).getAbsolutePath().equals(f.getAbsolutePath())) {
                            stripThumbs.get(idx).setImageBitmap(fb);
                        }
                    });
                }
            }
        });
    }

    // ================= 返回键（中途退出咨询） =================

    @Override
    public void onBackPressed() {
        if (screen == SCREEN_CULL) {
            if (applying) return;
            if (finished || decisions.isEmpty()) {
                showScreen(SCREEN_ALBUMS);
                loadAlbums();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("退出选片")
                    .setMessage(String.format(Locale.US,
                            "已划 %d 张（喜欢 %d）。\n\n「保留并结束」= 不喜欢的送回收站，喜欢的原地保留；\n「放弃更改」= 清空本次记录，不做任何更改。",
                            decisions.size(), likedCount()))
                    .setPositiveButton("保留并结束", (d, w) -> finishAndTrash())
                    .setNeutralButton("放弃更改", (d, w) -> {
                        synchronized (lock) { decisions.clear(); }
                        finished = false;
                        trashPending = false;
                        persistState();
                        showScreen(SCREEN_ALBUMS);
                        loadAlbums();
                    })
                    .setNegativeButton("继续选片", null)
                    .show();
        } else {
            finish();
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static void log(String s) {
        android.util.Log.e("PhotoCull", s);
    }
}
