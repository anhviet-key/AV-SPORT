package com.anhviet.avsport;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.anhviet.avsport.data.ApiClient;
import com.anhviet.avsport.data.SourceStore;
import com.anhviet.avsport.model.AppUpdateInfo;
import com.anhviet.avsport.model.MediaCard;
import com.anhviet.avsport.model.MediaSource;
import com.anhviet.avsport.model.SourceCategory;
import com.anhviet.avsport.model.SourceListResult;
import com.anhviet.avsport.model.SourceValidationResult;
import com.anhviet.avsport.ui.ImageLoader;
import com.anhviet.avsport.ui.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    public static final String EXTRA_CARD_TITLE = "card_title";
    public static final String EXTRA_CARD_PAGE_URL = "card_page_url";
    public static final String EXTRA_CARD_FALLBACK_URLS = "card_fallback_urls";

    private static final long AUTO_REFRESH_INTERVAL_MS = 90000; // 90 seconds silent refresh

    private final ExecutorService sourceExecutor = Executors.newFixedThreadPool(3);
    private final Handler autoRefreshHandler = new Handler(Looper.getMainLooper());
    private final ApiClient apiClient = new ApiClient();
    private final ImageLoader imageLoader = new ImageLoader();
    private final Map<String, SourceListResult> sourceCache = new HashMap<>();
    private final Set<String> loadingSourceIds = new HashSet<>();

    private SourceStore sourceStore;
    private List<MediaSource> sources = new ArrayList<>();
    private Map<String, Integer> sourceCounts = new HashMap<>();
    private String activeSourceId;
    private String activeCategoryKey;
    private int selectedCardIndex;

    private LinearLayout rootContent;
    private LinearLayout headerPanel;
    private TextView liveBadge;
    private TextView statusText;
    private Button refreshButton;
    private Button settingsButton;

    private HorizontalScrollView categoryScroller;
    private LinearLayout categoryRow;
    private LinearLayout cardSection;
    private RecyclerView cardList;
    private CardAdapter cardAdapter;
    private TextView emptyView;

    private final Runnable autoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !isDestroyed()) {
                silentRefreshActiveSource();
                autoRefreshHandler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            sourceStore = new SourceStore(this);
            sources = sourceStore.readSources();
            if (sources == null || sources.isEmpty()) {
                sources = SourceStore.defaultSources();
            }
            sourceCounts = sourceStore.readCounts();
            activeSourceId = sourceStore.readActiveSourceId();
            if (activeSourceId == null || activeSourceId.length() == 0) {
                activeSourceId = SourceStore.DEFAULT_SOURCE_ID;
            }
            activeCategoryKey = sourceStore.readActiveCategoryKey(activeSourceId);

            for (MediaSource source : sources) {
                SourceListResult cached = sourceStore.readCachedSource(source.id);
                if (cached != null) {
                    sourceCache.put(source.id, cached);
                    sourceCounts.put(source.id, activeItemCount(cached));
                }
            }

            buildLayout();
            renderAll();
            rootContent.post(this::focusActiveCategory);
            preloadAllSources();
        } catch (Throwable t) {
            android.util.Log.e("AVSport", "Error in onCreate", t);
            Toast.makeText(this, "Lỗi khởi động: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            silentRefreshActiveSource();
            startAutoRefreshTimer();
        } catch (Throwable t) {
            android.util.Log.e("AVSport", "Error in onResume", t);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAutoRefreshTimer();
    }

    @Override
    protected void onDestroy() {
        stopAutoRefreshTimer();
        sourceExecutor.shutdownNow();
        super.onDestroy();
    }

    private void startAutoRefreshTimer() {
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        autoRefreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS);
    }

    private void stopAutoRefreshTimer() {
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
    }

    private void silentRefreshActiveSource() {
        MediaSource source = findSource(activeSourceId);
        if (source != null) {
            loadSource(source, true, false);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isCardFocus(getCurrentFocus())) {
                focusActiveCategory();
                return true;
            }
            showExitDialog();
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (isDescendant(headerPanel, getCurrentFocus())) {
                focusActiveCategory();
                return true;
            }
            if (isDescendant(categoryScroller, getCurrentFocus())) {
                focusSelectedCard();
                return true;
            }
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (isCardFocus(getCurrentFocus()) && selectedCardIndex < columnsForScreen()) {
                focusActiveCategory();
                return true;
            }
            if (isDescendant(categoryScroller, getCurrentFocus())) {
                if (refreshButton != null) {
                    refreshButton.requestFocus();
                    return true;
                }
            }
        }

        return super.dispatchKeyEvent(event);
    }

    private void buildLayout() {
        rootContent = new LinearLayout(this);
        rootContent.setOrientation(LinearLayout.VERTICAL);
        rootContent.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 18));
        rootContent.setBackgroundColor(Ui.BG);
        setContentView(rootContent);

        // 1. Slim Top Header Bar
        headerPanel = new LinearLayout(this);
        headerPanel.setOrientation(LinearLayout.HORIZONTAL);
        headerPanel.setGravity(Gravity.CENTER_VERTICAL);
        headerPanel.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        headerPanel.setBackground(Ui.rounded(Ui.PANEL, 16, 1, Ui.BORDER_SOFT, this));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 62));
        headerParams.setMargins(0, 0, 0, Ui.dp(this, 10));
        rootContent.addView(headerPanel, headerParams);

        TextView logo = Ui.text(this, "AV SPORT", 24, Color.rgb(56, 189, 248), Typeface.BOLD);
        headerPanel.addView(logo);

        liveBadge = Ui.pill(this, "● TRỰC TIẾP", Ui.RED);
        LinearLayout.LayoutParams liveBadgeParams = new LinearLayout.LayoutParams(-2, -2);
        liveBadgeParams.setMargins(Ui.dp(this, 14), 0, 0, 0);
        headerPanel.addView(liveBadge, liveBadgeParams);

        statusText = Ui.text(this, "Đang tải dữ liệu...", 14, Ui.MUTED, Typeface.BOLD);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, -2, 1);
        statusParams.setMargins(Ui.dp(this, 14), 0, 0, 0);
        headerPanel.addView(statusText, statusParams);

        refreshButton = actionButton("🔄 Làm mới");
        refreshButton.setOnClickListener(view -> refreshActiveSource());
        headerPanel.addView(refreshButton, new LinearLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 42)));

        settingsButton = actionButton("⚙ Cài đặt");
        settingsButton.setOnClickListener(view -> showSettingsDialog());
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 42));
        settingsParams.setMargins(Ui.dp(this, 10), 0, 0, 0);
        headerPanel.addView(settingsButton, settingsParams);

        // 2. Categories Tab Row
        categoryRow = new LinearLayout(this);
        categoryRow.setOrientation(LinearLayout.HORIZONTAL);
        categoryRow.setGravity(Gravity.CENTER_VERTICAL);
        categoryScroller = horizontalScroller(categoryRow);
        LinearLayout.LayoutParams catParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 58));
        catParams.setMargins(0, 0, 0, Ui.dp(this, 10));
        rootContent.addView(categoryScroller, catParams);

        // 3. Match Grid Section (Takes all remaining screen height)
        cardSection = new LinearLayout(this);
        cardSection.setOrientation(LinearLayout.VERTICAL);
        cardSection.setBackground(Ui.rounded(Ui.PANEL, 16, 1, Ui.BORDER_SOFT, this));
        cardSection.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));

        emptyView = Ui.text(this, "", 22, Ui.TEXT, Typeface.BOLD);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(Ui.dp(this, 20), Ui.dp(this, 60), Ui.dp(this, 20), Ui.dp(this, 60));
        emptyView.setVisibility(View.GONE);
        cardSection.addView(emptyView, new LinearLayout.LayoutParams(-1, -2));

        cardAdapter = new CardAdapter();
        cardList = new RecyclerView(this);
        cardList.setHasFixedSize(true);
        cardList.setClipToPadding(false);
        cardList.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 50));
        cardList.setItemViewCacheSize(16);
        cardList.setLayoutManager(new GridLayoutManager(this, columnsForScreen()));
        cardList.setAdapter(cardAdapter);
        cardSection.addView(cardList, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout.LayoutParams cardSectionParams = new LinearLayout.LayoutParams(-1, 0, 1);
        rootContent.addView(cardSection, cardSectionParams);
    }

    private HorizontalScrollView horizontalScroller(LinearLayout child) {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(child, new HorizontalScrollView.LayoutParams(-2, -1));
        return scroller;
    }

    private Button actionButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Ui.TEXT);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        Ui.applyFocusBackground(button, Ui.CARD, Ui.CARD_FOCUS);
        return button;
    }

    private Button dangerButton(String label) {
        Button button = actionButton(label);
        button.setBackground(Ui.rounded(Color.rgb(69, 10, 10), 16, 2, Ui.RED, this));
        button.setOnFocusChangeListener((view, hasFocus) -> view.setBackground(Ui.rounded(
            hasFocus ? Ui.RED : Color.rgb(69, 10, 10),
            16,
            3,
            hasFocus ? Ui.YELLOW : Ui.RED,
            this
        )));
        return button;
    }

    private void showExitDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 28), Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24));
        content.setBackground(Ui.rounded(Ui.PANEL, 18, 1, Ui.BORDER_SOFT, this));

        TextView title = Ui.text(this, "Bạn muốn thoát AV Sport?", 26, Ui.TEXT, Typeface.BOLD);
        content.addView(title);

        TextView message = Ui.text(this, "Chọn Ở lại để tiếp tục xem, hoặc Thoát để đóng ứng dụng.", 16, Ui.MUTED, Typeface.BOLD);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, -2);
        messageParams.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 18));
        content.addView(message, messageParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        Button stay = actionButton("Ở lại");
        stay.setOnClickListener(view -> dialog.dismiss());
        actions.addView(stay, new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 54)));

        Button exit = dangerButton("Thoát");
        exit.setOnClickListener(view -> {
            dialog.dismiss();
            finish();
        });
        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 54));
        exitParams.setMargins(Ui.dp(this, 12), 0, 0, 0);
        actions.addView(exit, exitParams);

        content.addView(actions);
        dialog.setContentView(content);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(Ui.dp(this, 560), -2);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        stay.requestFocus();
    }

    private void styleCategoryButton(Button button, boolean active, boolean isLiveTab) {
        int activeBg = isLiveTab ? Color.rgb(153, 27, 27) : Color.rgb(28, 38, 30);
        int activeBorder = isLiveTab ? Ui.RED : Ui.GOLD;
        int normalBg = active ? activeBg : Color.rgb(18, 24, 20);
        int normalBorder = active ? activeBorder : Color.rgb(32, 42, 34);

        button.setTextColor(active ? (isLiveTab ? Color.WHITE : Ui.GOLD) : Color.rgb(156, 163, 175));
        button.setBackground(Ui.rounded(normalBg, 12, active ? 2 : 1, normalBorder, this));
        button.setOnFocusChangeListener((target, hasFocus) -> {
            target.animate().scaleX(hasFocus ? 1.05f : 1.0f).scaleY(hasFocus ? 1.05f : 1.0f).setDuration(120).start();
            button.setTextColor(hasFocus ? Color.WHITE : (active ? (isLiveTab ? Color.WHITE : Ui.GOLD) : Color.rgb(156, 163, 175)));
            target.setBackground(Ui.rounded(
                hasFocus ? Color.rgb(34, 46, 36) : normalBg,
                12,
                hasFocus || active ? 2 : 1,
                hasFocus ? Ui.GOLD : normalBorder,
                this
            ));
        });
    }

    private void focusSelectedCard() {
        if (cardAdapter == null || cardAdapter.getItemCount() == 0) {
            return;
        }
        int targetIndex = Math.max(0, Math.min(selectedCardIndex, cardAdapter.getItemCount() - 1));
        cardList.scrollToPosition(targetIndex);
        cardList.post(() -> {
            RecyclerView.ViewHolder holder = cardList.findViewHolderForAdapterPosition(targetIndex);
            if (holder != null) {
                holder.itemView.requestFocus();
            } else {
                cardList.postDelayed(this::focusSelectedCard, 60);
            }
        });
    }

    private void focusActiveCategory() {
        categoryScroller.post(() -> {
            if (requestCategoryFocus(activeCategoryKey)) {
                return;
            }
            if (categoryRow.getChildCount() > 0) {
                categoryRow.getChildAt(0).requestFocus();
            }
        });
    }

    private void focusCategory(String categoryKey) {
        categoryScroller.post(() -> requestCategoryFocus(categoryKey));
    }

    private boolean requestCategoryFocus(String categoryKey) {
        if (categoryKey == null) {
            return false;
        }
        for (int index = 0; index < categoryRow.getChildCount(); index++) {
            View child = categoryRow.getChildAt(index);
            Object tag = child.getTag();
            if (tag != null && tag.equals(categoryKey)) {
                child.requestFocus();
                return true;
            }
        }
        return false;
    }

    private boolean isCardFocus(View focus) {
        return isDescendant(cardList, focus);
    }

    private boolean isDescendant(View parent, View child) {
        if (parent == null || child == null) {
            return false;
        }
        View current = child;
        while (current != null) {
            if (current == parent) {
                return true;
            }
            if (!(current.getParent() instanceof View)) {
                return false;
            }
            current = (View) current.getParent();
        }
        return false;
    }

    private void renderAll() {
        renderContent();
    }

    private void renderContent() {
        SourceListResult result = sourceCache.get(activeSourceId);
        boolean loading = loadingSourceIds.contains(activeSourceId);

        if (result == null && !loading) {
            MediaSource current = findSource(activeSourceId);
            if (current != null) {
                loadSource(current, false, true);
            }
        }

        if (activeCategoryKey == null && result != null) {
            activeCategoryKey = pickDefaultCategory(result);
        } else if (result != null && !hasCategory(result, activeCategoryKey)) {
            activeCategoryKey = pickDefaultCategory(result);
        }

        renderCategories(result);

        List<MediaCard> items = visibleItems(result);
        if (loading && (result == null || result.items.isEmpty())) {
            renderEmpty("Đang tải danh sách trận đấu...");
        } else {
            renderCards(items);
        }
    }

    private void renderCategories(SourceListResult result) {
        categoryRow.removeAllViews();
        if (result == null) {
            return;
        }

        for (SourceCategory category : result.categories) {
            int visibleCount = countVisibleForCategory(result, category.key);
            Button button = new Button(this);
            boolean active = category.key.equals(activeCategoryKey);
            boolean isLiveTab = "truc-tiep".equals(category.key) || "live".equals(category.key);
            button.setAllCaps(false);
            button.setText((isLiveTab ? "● " : "") + category.label + " (" + visibleCount + ")");
            button.setTextSize(15);
            button.setTextColor(Ui.TEXT);
            button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            button.setGravity(Gravity.CENTER);
            button.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
            button.setMinWidth(Ui.dp(this, 120));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, Ui.dp(this, 44));
            params.setMargins(0, 0, Ui.dp(this, 10), 0);
            button.setLayoutParams(params);
            button.setTag(category.key);
            styleCategoryButton(button, active, isLiveTab);
            button.setOnClickListener(view -> {
                activeCategoryKey = category.key;
                selectedCardIndex = 0;
                sourceStore.writeActiveCategoryKey(activeSourceId, category.key);
                renderContent();
                focusCategory(category.key);
            });
            categoryRow.addView(button);
        }
    }

    private void renderCards(List<MediaCard> items) {
        if (items.isEmpty()) {
            renderEmpty("Mục này hiện chưa có trận nào diễn ra.");
            return;
        }

        if (selectedCardIndex >= items.size()) {
            selectedCardIndex = 0;
        }

        emptyView.setVisibility(View.GONE);
        cardList.setVisibility(View.VISIBLE);

        int liveCount = countLiveMatches(items);
        if (liveCount > 0) {
            liveBadge.setText("● " + liveCount + " TRẬN LIVE");
            liveBadge.setVisibility(View.VISIBLE);
        } else {
            liveBadge.setVisibility(View.GONE);
        }

        statusText.setText("Hiển thị " + items.size() + " trận | Chọn trận để xem trực tiếp");
        RecyclerView.LayoutManager layoutManager = cardList.getLayoutManager();
        if (layoutManager instanceof GridLayoutManager) {
            ((GridLayoutManager) layoutManager).setSpanCount(columnsForScreen());
        }
        cardAdapter.setItems(items);
    }

    private int countLiveMatches(List<MediaCard> items) {
        int count = 0;
        for (MediaCard item : items) {
            if (isItemLive(item)) {
                count++;
            }
        }
        return count;
    }

    private void renderEmpty(String message) {
        statusText.setText(message);
        if (cardAdapter != null) {
            cardAdapter.setItems(new ArrayList<>());
        }
        if (cardList != null) {
            cardList.setVisibility(View.GONE);
        }
        if (emptyView != null) {
            emptyView.setText(message);
            emptyView.setVisibility(View.VISIBLE);
        }
    }

    private boolean isPortraitPhone() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float dpWidth = dm.widthPixels / dm.density;
        int orientation = getResources().getConfiguration().orientation;
        return orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT && dpWidth < 600;
    }

    private int columnsForScreen() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float dpWidth = dm.widthPixels / dm.density;
        int orientation = getResources().getConfiguration().orientation;

        if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            return dpWidth >= 600 ? 2 : 1;
        } else {
            if (dpWidth >= 960) {
                return 4;
            } else if (dpWidth >= 600) {
                return 3;
            } else {
                return 2;
            }
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            int columns = columnsForScreen();
            androidx.recyclerview.widget.RecyclerView.LayoutManager lm = cardList.getLayoutManager();
            if (lm instanceof androidx.recyclerview.widget.GridLayoutManager) {
                ((androidx.recyclerview.widget.GridLayoutManager) lm).setSpanCount(columns);
            }
            if (cardAdapter != null) {
                cardAdapter.notifyDataSetChanged();
            }
        } catch (Exception ignored) {
        }
    }

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        private final List<MediaCard> items = new ArrayList<>();

        void setItems(List<MediaCard> nextItems) {
            items.clear();
            items.addAll(nextItems);
            notifyDataSetChanged();
        }

        @Override
        public CardHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            return new CardHolder();
        }

        @Override
        public void onBindViewHolder(CardHolder holder, int position) {
            holder.bind(items.get(position), position, items.size());
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private final class CardHolder extends RecyclerView.ViewHolder {
        private final FrameLayout frame;
        private final ImageView backgroundImage;
        private final View imageShade;

        // Top Header
        private final TextView leagueView;
        private final TextView statusBadge;

        // 3-Column Matchup Area (Colatv design)
        private final LinearLayout matchContainer;

        // Left: Home Team Column
        private final ImageView homeLogoView;
        private final TextView homeNameView;

        // Center: VS / LIVE info
        private final TextView centerLivePill;
        private final TextView centerVsOrScore;
        private final TextView centerTimePill;

        // Right: Away Team Column
        private final ImageView awayLogoView;
        private final TextView awayNameView;

        // Fallback Single Title (for rooms without 2 teams)
        private final LinearLayout singleTitleContainer;
        private final TextView singleTitleView;

        // Bottom Row: BLV pill
        private final LinearLayout commentatorPill;
        private final TextView commentatorText;

        CardHolder() {
            super(new FrameLayout(MainActivity.this));
            frame = (FrameLayout) itemView;
            frame.setFocusable(true);
            frame.setClickable(true);
            frame.setPadding(0, 0, 0, 0);
            frame.setClipToOutline(true);

            int cardHeight = isPortraitPhone() ? Ui.dp(MainActivity.this, 156) : Ui.dp(MainActivity.this, 172);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, cardHeight);
            params.setMargins(Ui.dp(MainActivity.this, 6), Ui.dp(MainActivity.this, 5), Ui.dp(MainActivity.this, 6), Ui.dp(MainActivity.this, 5));
            frame.setLayoutParams(params);

            backgroundImage = new ImageView(MainActivity.this);
            backgroundImage.setAlpha(0.12f);
            backgroundImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(backgroundImage, new FrameLayout.LayoutParams(-1, -1));

            imageShade = new View(MainActivity.this);
            imageShade.setBackgroundColor(0xEE090D0B);
            frame.addView(imageShade, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout content = new LinearLayout(MainActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 10), Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 10));
            frame.addView(content, new FrameLayout.LayoutParams(-1, -1));

            // 1. Top Header: League (Left) + Live/Time (Right)
            LinearLayout topRow = new LinearLayout(MainActivity.this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            leagueView = Ui.text(MainActivity.this, "", 12, Color.rgb(209, 213, 219), Typeface.BOLD);
            leagueView.setSingleLine(true);
            leagueView.setEllipsize(TextUtils.TruncateAt.END);
            topRow.addView(leagueView, new LinearLayout.LayoutParams(0, -2, 1));

            statusBadge = Ui.pill(MainActivity.this, "", Ui.CARD);
            topRow.addView(statusBadge, new LinearLayout.LayoutParams(-2, -2));
            content.addView(topRow, new LinearLayout.LayoutParams(-1, -2));

            // 2. Middle Matchup: Home (Left) | Center (VS/Score) | Away (Right)
            matchContainer = new LinearLayout(MainActivity.this);
            matchContainer.setOrientation(LinearLayout.HORIZONTAL);
            matchContainer.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams matchParams = new LinearLayout.LayoutParams(-1, 0, 1);
            matchParams.setMargins(0, Ui.dp(MainActivity.this, 4), 0, Ui.dp(MainActivity.this, 4));
            content.addView(matchContainer, matchParams);

            // Left: Home Team Column
            LinearLayout homeCol = new LinearLayout(MainActivity.this);
            homeCol.setOrientation(LinearLayout.VERTICAL);
            homeCol.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);

            homeLogoView = new ImageView(MainActivity.this);
            homeLogoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            homeCol.addView(homeLogoView, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 42), Ui.dp(MainActivity.this, 42)));

            homeNameView = Ui.text(MainActivity.this, "", 13, Ui.TEXT, Typeface.BOLD);
            homeNameView.setGravity(Gravity.CENTER);
            homeNameView.setMaxLines(2);
            homeNameView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams homeNameParams = new LinearLayout.LayoutParams(-1, -2);
            homeNameParams.setMargins(0, Ui.dp(MainActivity.this, 4), 0, 0);
            homeCol.addView(homeNameView, homeNameParams);

            LinearLayout.LayoutParams homeColParams = new LinearLayout.LayoutParams(0, -2, 38f);
            matchContainer.addView(homeCol, homeColParams);

            // Center: VS / LIVE Column
            LinearLayout centerCol = new LinearLayout(MainActivity.this);
            centerCol.setOrientation(LinearLayout.VERTICAL);
            centerCol.setGravity(Gravity.CENTER);

            centerLivePill = Ui.pill(MainActivity.this, "LIVE", Ui.RED);
            centerLivePill.setTextSize(10);
            centerLivePill.setPadding(Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 2), Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 2));
            centerLivePill.setVisibility(View.GONE);
            centerCol.addView(centerLivePill, new LinearLayout.LayoutParams(-2, -2));

            centerVsOrScore = Ui.text(MainActivity.this, "VS", 19, Ui.TEXT, Typeface.BOLD);
            centerVsOrScore.setGravity(Gravity.CENTER);
            centerCol.addView(centerVsOrScore, new LinearLayout.LayoutParams(-2, -2));

            centerTimePill = Ui.text(MainActivity.this, "", 11, Color.rgb(156, 163, 175), Typeface.NORMAL);
            centerTimePill.setGravity(Gravity.CENTER);
            centerTimePill.setPadding(Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 3), Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 3));
            centerTimePill.setBackground(Ui.rounded(Color.rgb(28, 36, 30), 999, 1, Color.rgb(40, 52, 44), MainActivity.this));
            LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(-2, -2);
            timeParams.setMargins(0, Ui.dp(MainActivity.this, 3), 0, 0);
            centerCol.addView(centerTimePill, timeParams);

            LinearLayout.LayoutParams centerColParams = new LinearLayout.LayoutParams(0, -2, 24f);
            matchContainer.addView(centerCol, centerColParams);

            // Right: Away Team Column
            LinearLayout awayCol = new LinearLayout(MainActivity.this);
            awayCol.setOrientation(LinearLayout.VERTICAL);
            awayCol.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);

            awayLogoView = new ImageView(MainActivity.this);
            awayLogoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            awayCol.addView(awayLogoView, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 42), Ui.dp(MainActivity.this, 42)));

            awayNameView = Ui.text(MainActivity.this, "", 13, Ui.TEXT, Typeface.BOLD);
            awayNameView.setGravity(Gravity.CENTER);
            awayNameView.setMaxLines(2);
            awayNameView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams awayNameParams = new LinearLayout.LayoutParams(-1, -2);
            awayNameParams.setMargins(0, Ui.dp(MainActivity.this, 4), 0, 0);
            awayCol.addView(awayNameView, awayNameParams);

            LinearLayout.LayoutParams awayColParams = new LinearLayout.LayoutParams(0, -2, 38f);
            matchContainer.addView(awayCol, awayColParams);

            // Single Title Container
            singleTitleContainer = new LinearLayout(MainActivity.this);
            singleTitleContainer.setOrientation(LinearLayout.VERTICAL);
            singleTitleContainer.setGravity(Gravity.CENTER);
            singleTitleView = Ui.text(MainActivity.this, "", 15, Ui.TEXT, Typeface.BOLD);
            singleTitleView.setMaxLines(2);
            singleTitleView.setGravity(Gravity.CENTER);
            singleTitleView.setEllipsize(TextUtils.TruncateAt.END);
            singleTitleContainer.addView(singleTitleView, new LinearLayout.LayoutParams(-1, -2));
            singleTitleContainer.setVisibility(View.GONE);
            content.addView(singleTitleContainer, new LinearLayout.LayoutParams(-1, 0, 1));

            // 3. Bottom Row: BLV Pill on Left
            LinearLayout bottomRow = new LinearLayout(MainActivity.this);
            bottomRow.setOrientation(LinearLayout.HORIZONTAL);
            bottomRow.setGravity(Gravity.CENTER_VERTICAL);

            commentatorPill = new LinearLayout(MainActivity.this);
            commentatorPill.setOrientation(LinearLayout.HORIZONTAL);
            commentatorPill.setGravity(Gravity.CENTER_VERTICAL);
            commentatorPill.setPadding(Ui.dp(MainActivity.this, 10), Ui.dp(MainActivity.this, 3), Ui.dp(MainActivity.this, 10), Ui.dp(MainActivity.this, 3));
            commentatorPill.setBackground(Ui.rounded(Color.rgb(24, 32, 26), 999, 1, Color.rgb(40, 52, 44), MainActivity.this));

            TextView micIcon = Ui.text(MainActivity.this, "🎙️ ", 11, Ui.GOLD, Typeface.NORMAL);
            commentatorPill.addView(micIcon);

            commentatorText = Ui.text(MainActivity.this, "", 11, Color.rgb(255, 255, 255), Typeface.BOLD);
            commentatorText.setSingleLine(true);
            commentatorText.setEllipsize(TextUtils.TruncateAt.END);
            commentatorPill.addView(commentatorText);

            bottomRow.addView(commentatorPill, new LinearLayout.LayoutParams(-2, -2));
            content.addView(bottomRow, new LinearLayout.LayoutParams(-1, -2));
        }

        void bind(MediaCard item, int index, int total) {
            int cardHeight = isPortraitPhone() ? Ui.dp(MainActivity.this, 156) : Ui.dp(MainActivity.this, 172);
            if (itemView.getLayoutParams().height != cardHeight) {
                itemView.getLayoutParams().height = cardHeight;
                itemView.requestLayout();
            }

            frame.setBackground(Ui.rounded(Color.rgb(18, 24, 20), 16, 1, Color.rgb(30, 40, 32), MainActivity.this));
            frame.setForeground(Ui.rounded(Color.TRANSPARENT, 16, 2, Color.TRANSPARENT, MainActivity.this));

            leagueView.setText("🏆 " + item.displayLeague());

            boolean live = isItemLive(item);
            if (live) {
                statusBadge.setText("● TRỰC TIẾP");
                statusBadge.setBackground(Ui.rounded(Ui.RED, 999, 0, 0, MainActivity.this));
                centerLivePill.setVisibility(View.VISIBLE);
                centerVsOrScore.setText(item.displayScore() != null ? item.displayScore() : "LIVE");
                centerVsOrScore.setTextColor(Color.rgb(239, 68, 68));
            } else {
                statusBadge.setText(item.displayTime());
                statusBadge.setBackground(Ui.rounded(Color.rgb(26, 34, 28), 999, 1, Color.rgb(44, 56, 48), MainActivity.this));
                centerLivePill.setVisibility(View.GONE);
                centerVsOrScore.setText("VS");
                centerVsOrScore.setTextColor(Color.WHITE);
            }
            centerTimePill.setText(item.displayTime());

            boolean hasTwoTeams = item.homeTeam != null && item.awayTeam != null && item.homeTeam.length() > 0 && item.awayTeam.length() > 0;
            if (hasTwoTeams) {
                matchContainer.setVisibility(View.VISIBLE);
                singleTitleContainer.setVisibility(View.GONE);

                homeNameView.setText(item.homeTeam);
                awayNameView.setText(item.awayTeam);

                bindLogo(homeLogoView, item.homeLogo);
                bindLogo(awayLogoView, item.awayLogo);
            } else {
                matchContainer.setVisibility(View.GONE);
                singleTitleContainer.setVisibility(View.VISIBLE);
                singleTitleView.setText(item.displayTitle());
            }

            if (item.commentator != null && item.commentator.length() > 0) {
                commentatorPill.setVisibility(View.VISIBLE);
                commentatorText.setText(item.commentator);
            } else {
                commentatorPill.setVisibility(View.GONE);
            }

            bindImage(backgroundImage, bestBackgroundImage(item));
            imageShade.setVisibility(bestBackgroundImage(item) == null ? View.GONE : View.VISIBLE);

            frame.setOnClickListener(view -> openPlayer(item));
            frame.setOnFocusChangeListener((view, hasFocus) -> {
                view.animate().scaleX(hasFocus ? 1.03f : 1.0f).scaleY(hasFocus ? 1.03f : 1.0f).setDuration(120).start();
                view.setElevation(hasFocus ? Ui.dp(MainActivity.this, 8) : Ui.dp(MainActivity.this, 2));
                frame.setBackground(Ui.rounded(
                    hasFocus ? Color.rgb(26, 36, 28) : Color.rgb(18, 24, 20),
                    16,
                    hasFocus ? 2 : 1,
                    hasFocus ? Ui.GOLD : Color.rgb(30, 40, 32),
                    MainActivity.this
                ));
                frame.setForeground(Ui.rounded(Color.TRANSPARENT, 16, 2, hasFocus ? Ui.GOLD : Color.TRANSPARENT, MainActivity.this));
                if (hasFocus) {
                    selectedCardIndex = index;
                    statusText.setText("Đang chọn " + (index + 1) + " / " + total + " | " + item.displayTitle());
                }
            });
        }

        private void bindLogo(ImageView view, String url) {
            if (url == null || url.length() == 0) {
                view.setImageDrawable(null);
                view.setVisibility(View.GONE);
                return;
            }
            view.setVisibility(View.VISIBLE);
            imageLoader.load(view, apiClient.toAbsoluteUrl(url));
        }

        private String bestBackgroundImage(MediaCard item) {
            if (item.image != null) {
                return item.image;
            }
            if (item.homeLogo != null) {
                return item.homeLogo;
            }
            return item.awayLogo;
        }

        private void bindImage(ImageView view, String url) {
            if (url == null || url.length() == 0) {
                view.setImageDrawable(null);
                view.setVisibility(View.GONE);
                return;
            }
            view.setVisibility(View.VISIBLE);
            imageLoader.load(view, apiClient.toAbsoluteUrl(url));
        }
    }

    private void openPlayer(MediaCard item) {
        if (item == null) {
            return;
        }
        try {
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra(EXTRA_CARD_PAGE_URL, item.pageUrl);
            intent.putExtra(EXTRA_CARD_TITLE, item.displayTitle());
            intent.putStringArrayListExtra(EXTRA_CARD_FALLBACK_URLS, collectFallbackUrls(item));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Không thể mở luồng phát: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private ArrayList<String> collectFallbackUrls(MediaCard selectedItem) {
        ArrayList<String> urls = new ArrayList<>();
        if (selectedItem.pageUrl != null && selectedItem.pageUrl.length() > 0) {
            urls.add(selectedItem.pageUrl);
        }

        for (Map.Entry<String, SourceListResult> entry : sourceCache.entrySet()) {
            if (entry.getKey().equals(activeSourceId)) {
                continue;
            }
            SourceListResult cached = entry.getValue();
            if (cached == null) {
                continue;
            }
            for (MediaCard candidate : cached.items) {
                if (candidate.pageUrl == null || candidate.pageUrl.length() == 0) {
                    continue;
                }
                if (isSameMatchTitle(selectedItem, candidate) && !urls.contains(candidate.pageUrl)) {
                    urls.add(candidate.pageUrl);
                }
            }
        }
        return urls;
    }

    private boolean isSameMatchTitle(MediaCard left, MediaCard right) {
        if (left == null || right == null) {
            return false;
        }
        String leftKey = normalizeMatchKey(left.displayTitle());
        String rightKey = normalizeMatchKey(right.displayTitle());
        if (leftKey.length() == 0 || rightKey.length() == 0) {
            return false;
        }
        if (leftKey.equals(rightKey)) {
            return true;
        }

        Set<String> leftTokens = matchTokens(leftKey);
        Set<String> rightTokens = matchTokens(rightKey);
        if (leftTokens.size() < 2 || rightTokens.size() < 2) {
            return false;
        }

        int matches = 0;
        for (String token : leftTokens) {
            if (rightTokens.contains(token)) {
                matches++;
            }
        }

        return matches >= Math.min(2, Math.min(leftTokens.size(), rightTokens.size()));
    }

    private Set<String> matchTokens(String value) {
        Set<String> tokens = new HashSet<>();
        for (String item : value.split("\\s+")) {
            if (item.length() >= 3 && !item.equals("vs") && !item.equals("fc")) {
                tokens.add(item);
            }
        }
        return tokens;
    }

    private String normalizeMatchKey(String value) {
        if (value == null) {
            return "";
        }
        return value
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9\\p{L}]+", " ")
            .trim();
    }

    private void preloadAllSources() {
        for (MediaSource source : sources) {
            boolean hasCachedData = sourceCache.containsKey(source.id);
            loadSource(source, false, !hasCachedData && source.id.equals(activeSourceId));
        }
    }

    private void refreshActiveSource() {
        MediaSource source = findSource(activeSourceId);
        if (source == null) {
            return;
        }
        sourceCache.remove(source.id);
        sourceStore.clearCachedSource(source.id);
        Toast.makeText(this, "Đang làm mới danh sách trận đấu...", Toast.LENGTH_SHORT).show();
        loadSource(source, true, true);
    }

    private void loadSource(MediaSource source, boolean forceRefresh, boolean showActiveLoading) {
        if (loadingSourceIds.contains(source.id)) {
            return;
        }
        loadingSourceIds.add(source.id);
        if (showActiveLoading) {
            renderContent();
        }
        sourceExecutor.execute(() -> {
            try {
                SourceListResult result = apiClient.fetchSourceList(source.url, forceRefresh);
                runOnUiThread(() -> {
                    int count = activeItemCount(result);
                    loadingSourceIds.remove(source.id);
                    sourceCache.put(source.id, result);
                    sourceCounts.put(source.id, count);
                    sourceStore.writeCachedSource(source.id, result);
                    sourceStore.writeCount(source.id, count);
                    if (source.id.equals(activeSourceId)) {
                        renderContent();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    loadingSourceIds.remove(source.id);
                    if (source.id.equals(activeSourceId)) {
                        if (showActiveLoading) {
                            Toast.makeText(this, "Không tải được nguồn: " + error.getMessage(), Toast.LENGTH_LONG).show();
                        }
                        renderContent();
                    }
                });
            }
        });
    }

    private void showSettingsDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 28), Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24));
        root.setBackground(Ui.rounded(Ui.PANEL, 20, 1, Ui.BORDER_SOFT, this));

        TextView title = Ui.text(this, "Cài đặt ứng dụng", 26, Ui.TEXT, Typeface.BOLD);
        root.addView(title);

        TextView info = Ui.text(this, "AV Sport Android TV\nPhiên bản: " + BuildConfig.VERSION_NAME + " (Socolive Native)", 16, Ui.MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 18));
        root.addView(info, infoParams);

        Button updateButton = actionButton("🚀 Kiểm tra cập nhật phiên bản mới");
        updateButton.setOnClickListener(view -> checkForUpdate());
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54));
        updateParams.setMargins(0, 0, 0, Ui.dp(this, 12));
        root.addView(updateButton, updateParams);

        Button closeButton = actionButton("Đóng");
        closeButton.setOnClickListener(view -> dialog.dismiss());
        root.addView(closeButton, new LinearLayout.LayoutParams(-1, Ui.dp(this, 54)));

        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(Ui.dp(this, 500), -2);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        updateButton.requestFocus();
    }

    private void checkForUpdate() {
        Toast.makeText(this, "Đang kiểm tra cập nhật...", Toast.LENGTH_SHORT).show();
        sourceExecutor.execute(() -> {
            try {
                AppUpdateInfo updateInfo = apiClient.fetchAppUpdate();
                runOnUiThread(() -> showUpdateResult(updateInfo));
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this, "Không kiểm tra được cập nhật: " + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showUpdateResult(AppUpdateInfo updateInfo) {
        if (updateInfo.versionCode <= BuildConfig.VERSION_CODE) {
            Toast.makeText(this, "Đang dùng bản mới nhất: " + BuildConfig.VERSION_NAME, Toast.LENGTH_LONG).show();
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 28), Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24));
        content.setBackground(Ui.rounded(Ui.PANEL, 24, 2, Ui.FOCUS, this));

        TextView title = Ui.text(this, "Có bản cập nhật mới", 26, Ui.TEXT, Typeface.BOLD);
        content.addView(title);

        String detail = "Bản hiện tại: " + BuildConfig.VERSION_NAME
            + "\nBản mới: " + updateInfo.version
            + (updateInfo.publishedAt.length() > 0 ? "\nNgày phát hành: " + updateInfo.publishedAt : "");
        TextView message = Ui.text(this, detail, 16, Ui.MUTED, Typeface.BOLD);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, -2);
        messageParams.setMargins(0, Ui.dp(this, 14), 0, Ui.dp(this, 18));
        content.addView(message, messageParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button download = actionButton(updateInfo.required ? "Tải và cài đặt ngay" : "Tải và cài đặt");
        download.setOnClickListener(view -> openUpdateUrl(updateInfo.apkUrl));
        actions.addView(download, new LinearLayout.LayoutParams(Ui.dp(this, 230), Ui.dp(this, 56)));

        Button close = actionButton("Đóng");
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 56));
        closeParams.setMargins(Ui.dp(this, 12), 0, 0, 0);
        close.setOnClickListener(view -> dialog.dismiss());
        actions.addView(close, closeParams);
        content.addView(actions);

        dialog.setContentView(content);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(Ui.dp(this, 560), -2);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        download.requestFocus();
    }

    private void openUpdateUrl(String apkUrl) {
        if (apkUrl == null || apkUrl.length() == 0) {
            Toast.makeText(this, "Manifest cập nhật chưa có link APK.", Toast.LENGTH_LONG).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Bật quyền cài APK cho AV Sport rồi bấm cập nhật lại.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }

        downloadAndInstallUpdate(apkUrl);
    }

    private void downloadAndInstallUpdate(String apkUrl) {
        Toast.makeText(this, "Đang tải APK cập nhật...", Toast.LENGTH_LONG).show();
        sourceExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(apkUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(120000);
                connection.setRequestProperty("User-Agent", "AV Sport Android TV/" + BuildConfig.VERSION_NAME);

                int statusCode = connection.getResponseCode();
                if (statusCode < 200 || statusCode >= 300) {
                    throw new IllegalStateException("HTTP " + statusCode);
                }

                File updateDir = new File(getCacheDir(), "updates");
                if (!updateDir.exists() && !updateDir.mkdirs()) {
                    throw new IllegalStateException("Không tạo được thư mục cập nhật.");
                }

                File apkFile = new File(updateDir, "av-sport-update.apk");
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(apkFile, false)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }

                runOnUiThread(() -> installDownloadedApk(apkFile, apkUrl));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Không tải được APK, mở link tải ngoài.", Toast.LENGTH_LONG).show();
                    openExternalDownloadUrl(apkUrl);
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void installDownloadedApk(File apkFile, String fallbackUrl) {
        try {
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            Toast.makeText(this, "Đã tải xong. Xác nhận cài đặt trên màn hình TV.", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException error) {
            openExternalDownloadUrl(fallbackUrl);
        } catch (IllegalArgumentException error) {
            openExternalDownloadUrl(fallbackUrl);
        }
    }

    private void openExternalDownloadUrl(String apkUrl) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "TV Box không có ứng dụng mở link tải APK.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasCategory(SourceListResult result, String key) {
        for (SourceCategory category : result.categories) {
            if (category.key.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private String pickDefaultCategory(SourceListResult result) {
        for (SourceCategory category : result.categories) {
            if (countVisibleForCategory(result, category.key) > 0) {
                return category.key;
            }
        }
        return result.categories.isEmpty() ? null : result.categories.get(0).key;
    }

    private List<MediaCard> visibleItems(SourceListResult result) {
        List<MediaCard> items = new ArrayList<>();
        if (result == null) {
            return items;
        }
        if (activeCategoryKey == null || "tat-ca".equals(activeCategoryKey) || "all".equals(activeCategoryKey)) {
            for (MediaCard item : result.items) {
                if (!item.isEnded()) {
                    items.add(item);
                }
            }
            return items;
        }
        if ("truc-tiep".equals(activeCategoryKey) || "live".equals(activeCategoryKey)) {
            for (MediaCard item : result.items) {
                if (!item.isEnded() && isItemLive(item)) {
                    items.add(item);
                }
            }
            return items;
        }
        for (MediaCard item : result.items) {
            if (!item.isEnded() && activeCategoryKey.equals(item.sportKey)) {
                items.add(item);
            }
        }
        return items;
    }

    private int activeItemCount(SourceListResult result) {
        int count = 0;
        if (result == null) {
            return count;
        }
        for (MediaCard item : result.items) {
            if (!item.isEnded()) {
                count++;
            }
        }
        return count;
    }

    private int countVisibleForCategory(SourceListResult result, String categoryKey) {
        int count = 0;
        if (result == null || categoryKey == null) {
            return count;
        }
        if ("tat-ca".equals(categoryKey) || "all".equals(categoryKey)) {
            for (MediaCard item : result.items) {
                if (!item.isEnded()) {
                    count++;
                }
            }
            return count;
        }
        if ("truc-tiep".equals(categoryKey) || "live".equals(categoryKey)) {
            for (MediaCard item : result.items) {
                if (!item.isEnded() && isItemLive(item)) {
                    count++;
                }
            }
            return count;
        }
        for (MediaCard item : result.items) {
            if (!item.isEnded() && categoryKey.equals(item.sportKey)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isItemLive(MediaCard item) {
        if (item == null) return false;
        String status = item.displayStatus().toLowerCase(Locale.ROOT);
        String label = item.statusLabel != null ? item.statusLabel.toLowerCase(Locale.ROOT) : "";
        return status.contains("live") || status.contains("trực") || label.contains("trực") || label.contains("live");
    }

    private MediaSource findSource(String sourceId) {
        if (sourceId != null && sources != null) {
            for (MediaSource source : sources) {
                if (source.id.equals(sourceId)) {
                    return source;
                }
            }
        }
        if (sources != null && !sources.isEmpty()) {
            return sources.get(0);
        }
        return new MediaSource(SourceStore.DEFAULT_SOURCE_ID, "Socolive Trực Tiếp", "socolive://live");
    }
}
