package com.anhviet.avsport;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
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

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.content.FileProvider;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    public static final String EXTRA_CARD_TITLE = "card_title";
    public static final String EXTRA_CARD_PAGE_URL = "card_page_url";
    public static final String EXTRA_CARD_FALLBACK_URLS = "card_fallback_urls";

    private final ExecutorService sourceExecutor = Executors.newFixedThreadPool(3);
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
    private LinearLayout sourceRow;
    private HorizontalScrollView categoryScroller;
    private LinearLayout categoryRow;
    private FrameLayout featuredPanel;
    private LinearLayout cardSection;
    private RecyclerView cardList;
    private CardAdapter cardAdapter;
    private TextView emptyView;
    private TextView statusText;
    private boolean topChromeCollapsed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sourceStore = new SourceStore(this);
        sources = sourceStore.readSources();
        sourceCounts = sourceStore.readCounts();
        activeSourceId = sourceStore.readActiveSourceId();
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
        rootContent.post(this::focusActiveSource);
        preloadAllSources();
    }

    @Override
    protected void onDestroy() {
        sourceExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (topChromeCollapsed) {
                setTopChromeCollapsed(false, true);
            } else {
                showExitDialog();
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !topChromeCollapsed) {
            if (isDescendant(categoryScroller, getCurrentFocus())) {
                setTopChromeCollapsed(true, true);
                return true;
            }
            if (isDescendant(headerPanel, getCurrentFocus())) {
                focusActiveCategory();
                return true;
            }
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && topChromeCollapsed && isCardFocus(getCurrentFocus()) && selectedCardIndex < columnsForScreen()) {
            setTopChromeCollapsed(false, true);
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    private void buildLayout() {
        rootContent = new LinearLayout(this);
        rootContent.setOrientation(LinearLayout.VERTICAL);
        rootContent.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 24));
        rootContent.setBackgroundColor(Ui.BG);
        setContentView(rootContent);

        headerPanel = panel();
        sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView sourceScroller = horizontalScroller(sourceRow);
        headerPanel.addView(sourceScroller, new LinearLayout.LayoutParams(-1, Ui.dp(this, 96)));
        rootContent.addView(headerPanel);

        featuredPanel = new FrameLayout(this);
        featuredPanel.setBackground(Ui.rounded(Ui.PANEL, 18, 1, Ui.BORDER_SOFT, this));
        LinearLayout.LayoutParams featuredParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 170));
        featuredParams.setMargins(0, 0, 0, Ui.dp(this, 14));
        rootContent.addView(featuredPanel, featuredParams);

        categoryRow = new LinearLayout(this);
        categoryRow.setOrientation(LinearLayout.HORIZONTAL);
        categoryScroller = horizontalScroller(categoryRow);
        rootContent.addView(categoryScroller, new LinearLayout.LayoutParams(-1, Ui.dp(this, 88)));

        cardSection = panel();
        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, 0, 0, Ui.dp(this, 12));
        statusText = Ui.text(this, "", 14, Ui.MUTED, Typeface.BOLD);
        topBar.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
        Button refreshButton = actionButton("Tải lại nguồn");
        refreshButton.setOnClickListener(view -> refreshActiveSource());
        topBar.addView(refreshButton);
        cardSection.addView(topBar);

        emptyView = Ui.text(this, "", 24, Ui.TEXT, Typeface.BOLD);
        emptyView.setPadding(Ui.dp(this, 20), Ui.dp(this, 40), Ui.dp(this, 20), Ui.dp(this, 40));
        emptyView.setVisibility(View.GONE);
        cardSection.addView(emptyView, new LinearLayout.LayoutParams(-1, -2));

        cardAdapter = new CardAdapter();
        cardList = new RecyclerView(this);
        cardList.setHasFixedSize(true);
        cardList.setClipToPadding(false);
        cardList.setItemViewCacheSize(12);
        cardList.setLayoutManager(new GridLayoutManager(this, columnsForScreen()));
        cardList.setAdapter(cardAdapter);
        cardSection.addView(cardList, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout.LayoutParams cardSectionParams = new LinearLayout.LayoutParams(-1, 0, 1);
        rootContent.addView(cardSection, cardSectionParams);
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        panel.setBackground(Ui.rounded(Ui.PANEL, 18, 1, Ui.BORDER_SOFT, this));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, Ui.dp(this, 14));
        panel.setLayoutParams(params);
        return panel;
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
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
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
        actions.addView(stay, new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 58)));

        Button exit = dangerButton("Thoát");
        exit.setOnClickListener(view -> {
            dialog.dismiss();
            finish();
        });
        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 58));
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

    private void styleFocusablePanel(View view, boolean active) {
        int activeColor = Color.rgb(6, 78, 59);
        int normalColor = active ? activeColor : Ui.CARD;
        int normalBorder = active ? Ui.FOCUS : Ui.BORDER;
        view.setBackground(Ui.rounded(normalColor, 20, active ? 3 : 2, normalBorder, this));
        view.setSelected(active);
        view.setActivated(active);
        view.setOnFocusChangeListener((target, hasFocus) -> target.setBackground(Ui.rounded(
            hasFocus ? Ui.CARD_FOCUS : normalColor,
            20,
            hasFocus || active ? 3 : 2,
            hasFocus ? Ui.YELLOW : normalBorder,
            this
        )));
    }

    private void setTopChromeCollapsed(boolean collapsed, boolean moveFocus) {
        if (topChromeCollapsed == collapsed) {
            if (moveFocus && collapsed) {
                focusSelectedCard();
            }
            return;
        }

        topChromeCollapsed = collapsed;
        int visibility = collapsed ? View.GONE : View.VISIBLE;
        headerPanel.setVisibility(visibility);
        featuredPanel.setVisibility(visibility);
        categoryScroller.setVisibility(visibility);

        if (moveFocus) {
            if (collapsed) {
                focusSelectedCard();
            } else {
                focusActiveCategory();
            }
        }
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
                cardList.postDelayed(this::focusSelectedCard, 80);
            }
        });
    }

    private void focusActiveCategory() {
        categoryScroller.post(() -> {
            if (requestCategoryFocus(activeCategoryKey)) {
                return;
            }
            if (sourceRow.getChildCount() > 0) {
                sourceRow.getChildAt(0).requestFocus();
            }
        });
    }

    private void focusActiveSource() {
        sourceRow.post(() -> {
            for (int index = 0; index < sourceRow.getChildCount(); index++) {
                View child = sourceRow.getChildAt(index);
                Object tag = child.getTag();
                if (tag != null && tag.equals(activeSourceId)) {
                    child.requestFocus();
                    return;
                }
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

    private boolean isTopChromeFocus(View focus) {
        return isDescendant(headerPanel, focus) || isDescendant(featuredPanel, focus) || isDescendant(categoryScroller, focus);
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
        renderSources();
        renderContent();
    }

    private void renderSources() {
        sourceRow.removeAllViews();
        for (MediaSource source : sources) {
            sourceRow.addView(sourceButton(source));
        }
        Button settingsButton = sourceButtonBase("Cài đặt", "Thêm nguồn", "⚙");
        styleFocusablePanel(settingsButton, false);
        settingsButton.setOnClickListener(view -> showSettingsDialog());
        sourceRow.addView(settingsButton);
    }

    private Button sourceButton(MediaSource source) {
        String prefix = source.id.equals(activeSourceId) ? "Đang mở" : "Nguồn";
        String count = sourceCounts.containsKey(source.id) ? String.valueOf(sourceCounts.get(source.id)) : "--";
        Button button = sourceButtonBase(prefix, source.name, count);
        button.setTag(source.id);
        styleFocusablePanel(button, source.id.equals(activeSourceId));
        button.setOnClickListener(view -> selectSource(source.id));
        return button;
    }

    private Button sourceButtonBase(String prefix, String title, String count) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(prefix + "\n" + title + "   " + count);
        button.setTextColor(Ui.TEXT);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        button.setSingleLine(false);
        button.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(this, 190), -1);
        params.setMargins(0, 0, Ui.dp(this, 12), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void selectSource(String sourceId) {
        activeSourceId = sourceId;
        activeCategoryKey = sourceStore.readActiveCategoryKey(sourceId);
        selectedCardIndex = 0;
        sourceStore.writeActiveSourceId(sourceId);
        renderAll();
        focusActiveSource();
        MediaSource source = findSource(sourceId);
        if (source != null && !sourceCache.containsKey(sourceId)) {
            loadSource(source, false, true);
        }
    }

    private void renderContent() {
        MediaSource activeSource = findSource(activeSourceId);
        SourceListResult result = activeSource == null ? null : sourceCache.get(activeSource.id);
        boolean isLoading = activeSource != null && loadingSourceIds.contains(activeSource.id);

        if (result == null) {
            renderFeatured(activeSource, null, 0, isLoading);
            renderCategories(null);
            renderEmpty(isLoading ? "Đang tải nguồn..." : "Chưa có dữ liệu nguồn.");
            return;
        }

        if (activeCategoryKey == null || !hasCategory(result, activeCategoryKey)) {
            activeCategoryKey = pickDefaultCategory(result);
            sourceStore.writeActiveCategoryKey(activeSource.id, activeCategoryKey);
        }

        List<MediaCard> visibleItems = visibleItems(result);
        renderFeatured(activeSource, visibleItems.isEmpty() ? null : visibleItems.get(Math.min(selectedCardIndex, visibleItems.size() - 1)), visibleItems.size(), false);
        renderCategories(result);
        renderCards(visibleItems);
    }

    private void renderFeatured(MediaSource source, MediaCard selectedItem, int visibleCount, boolean loading) {
        featuredPanel.removeAllViews();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        featuredPanel.addView(content, new FrameLayout.LayoutParams(-1, -1));

        String badge = source == null ? "AV Sport" : source.name;
        TextView badgeView = Ui.pill(this, badge + "  " + visibleCount + " trận", Ui.CARD);
        content.addView(badgeView);

        TextView title = Ui.text(
            this,
            loading ? "Đang tải danh sách trận đấu" : selectedItem == null ? "Thêm nguồn để lấy trận đấu" : selectedItem.displayTitle(),
            34,
            Ui.TEXT,
            Typeface.BOLD
        );
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(0, Ui.dp(this, 18), 0, 0);
        content.addView(title, titleParams);

        TextView subtitle = Ui.text(
            this,
            selectedItem == null ? "Dùng remote để chọn nguồn, tab thể thao và trận đấu." : selectedItem.displayLeague(),
            16,
            Ui.MUTED,
            Typeface.NORMAL
        );
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.setMargins(0, Ui.dp(this, 10), 0, 0);
        content.addView(subtitle, subParams);
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
            button.setAllCaps(false);
            button.setText(category.label + "\n" + visibleCount + " trận");
            button.setTextSize(15);
            button.setTextColor(Ui.TEXT);
            button.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            button.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(this, 170), -1);
            params.setMargins(0, 0, Ui.dp(this, 12), 0);
            button.setLayoutParams(params);
            button.setTag(category.key);
            styleFocusablePanel(button, active);
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
            renderEmpty("Tab này hiện chưa có trận hiển thị.");
            return;
        }

        if (selectedCardIndex >= items.size()) {
            selectedCardIndex = 0;
        }

        emptyView.setVisibility(View.GONE);
        cardList.setVisibility(View.VISIBLE);
        statusText.setText("Đang chọn " + (Math.min(selectedCardIndex + 1, items.size())) + " / " + items.size());
        RecyclerView.LayoutManager layoutManager = cardList.getLayoutManager();
        if (layoutManager instanceof GridLayoutManager) {
            ((GridLayoutManager) layoutManager).setSpanCount(columnsForScreen());
        }
        cardAdapter.setItems(items);
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

    private int columnsForScreen() {
        return getResources().getDisplayMetrics().widthPixels >= 1600 ? 4 : 3;
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
        private final ImageView homeLogo;
        private final ImageView awayLogo;
        private final TextView time;
        private final TextView status;
        private final TextView title;
        private final TextView league;

        CardHolder() {
            super(new FrameLayout(MainActivity.this));
            frame = (FrameLayout) itemView;
            frame.setFocusable(true);
            frame.setClickable(true);
            frame.setPadding(0, 0, 0, 0);
            frame.setClipToOutline(true);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, Ui.dp(MainActivity.this, 292));
            params.setMargins(Ui.dp(MainActivity.this, 6), Ui.dp(MainActivity.this, 6), Ui.dp(MainActivity.this, 6), Ui.dp(MainActivity.this, 6));
            frame.setLayoutParams(params);

            backgroundImage = new ImageView(MainActivity.this);
            backgroundImage.setAlpha(0.36f);
            backgroundImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(backgroundImage, new FrameLayout.LayoutParams(-1, -1));

            imageShade = new View(MainActivity.this);
            imageShade.setBackgroundColor(0xAA02070D);
            frame.addView(imageShade, new FrameLayout.LayoutParams(-1, -1));

            homeLogo = new ImageView(MainActivity.this);
            homeLogo.setAlpha(0.55f);
            homeLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams homeParams = new FrameLayout.LayoutParams(Ui.dp(MainActivity.this, 120), Ui.dp(MainActivity.this, 120), Gravity.LEFT | Gravity.CENTER_VERTICAL);
            homeParams.setMargins(Ui.dp(MainActivity.this, 10), 0, 0, 0);
            frame.addView(homeLogo, homeParams);

            awayLogo = new ImageView(MainActivity.this);
            awayLogo.setAlpha(0.55f);
            awayLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams awayParams = new FrameLayout.LayoutParams(Ui.dp(MainActivity.this, 120), Ui.dp(MainActivity.this, 120), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            awayParams.setMargins(0, 0, Ui.dp(MainActivity.this, 10), 0);
            frame.addView(awayLogo, awayParams);

            LinearLayout top = new LinearLayout(MainActivity.this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            time = Ui.pill(MainActivity.this, "", Ui.CARD);
            top.addView(time);
            status = Ui.pill(MainActivity.this, "", Ui.GREEN);
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
            statusParams.setMargins(Ui.dp(MainActivity.this, 10), 0, 0, 0);
            top.addView(status, statusParams);
            FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
            topParams.setMargins(0, Ui.dp(MainActivity.this, 12), Ui.dp(MainActivity.this, 12), 0);
            frame.addView(top, topParams);

            LinearLayout bottom = new LinearLayout(MainActivity.this);
            bottom.setOrientation(LinearLayout.VERTICAL);
            bottom.setGravity(Gravity.BOTTOM);
            title = Ui.text(MainActivity.this, "", 22, Ui.TEXT, Typeface.BOLD);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            bottom.addView(title);
            league = Ui.text(MainActivity.this, "", 14, Ui.MUTED, Typeface.NORMAL);
            league.setSingleLine(true);
            league.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams leagueParams = new LinearLayout.LayoutParams(-1, -2);
            leagueParams.setMargins(0, Ui.dp(MainActivity.this, 8), 0, 0);
            bottom.addView(league, leagueParams);
            FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            bottomParams.setMargins(Ui.dp(MainActivity.this, 14), 0, Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 14));
            frame.addView(bottom, bottomParams);
        }

        void bind(MediaCard item, int index, int total) {
            frame.setBackground(Ui.rounded(Ui.CARD, 14, 0, Ui.BORDER, MainActivity.this));
            frame.setForeground(Ui.rounded(Color.TRANSPARENT, 14, 3, Ui.BORDER, MainActivity.this));
            time.setText(item.displayTime());
            status.setText(item.displayStatus());
            status.setBackground(Ui.rounded(statusColor(item.displayStatus()), 999, 1, Ui.BORDER, MainActivity.this));
            title.setText(item.displayTitle());
            league.setText(item.displayLeague());
            bindImage(backgroundImage, bestBackgroundImage(item));
            imageShade.setVisibility(bestBackgroundImage(item) == null ? View.GONE : View.VISIBLE);
            bindImage(homeLogo, item.homeLogo);
            bindImage(awayLogo, item.awayLogo);

            frame.setOnClickListener(view -> openPlayer(item));
            frame.setOnFocusChangeListener((view, hasFocus) -> {
                frame.setBackground(Ui.rounded(hasFocus ? Ui.CARD_FOCUS : Ui.CARD, 14, 0, Ui.BORDER, MainActivity.this));
                frame.setForeground(Ui.rounded(Color.TRANSPARENT, 14, 3, hasFocus ? Ui.FOCUS : Ui.BORDER, MainActivity.this));
                if (hasFocus) {
                    selectedCardIndex = index;
                    renderFeatured(findSource(activeSourceId), item, total, false);
                    statusText.setText("Đang chọn " + (index + 1) + " / " + total);
                }
            });
        }

        private String bestBackgroundImage(MediaCard item) {
            if (item.image != null) {
                return item.image;
            }
            if (item.competitionLogo != null) {
                return item.competitionLogo;
            }
            if (item.homeLogo != null) {
                return item.homeLogo;
            }
            return item.awayLogo;
        }

        private void bindImage(ImageView target, String url) {
            target.setImageDrawable(null);
            target.setTag(url);
            if (url == null || url.length() == 0) {
                target.setVisibility(View.INVISIBLE);
                return;
            }
            target.setVisibility(View.VISIBLE);
            imageLoader.load(target, apiClient.toAbsoluteUrl(url));
        }
    }

    private int statusColor(String status) {
        String normalized = status == null ? "" : status.toLowerCase();
        if (normalized.contains("live") || normalized.contains("trực") || normalized.contains("đang")) {
            return Ui.RED;
        }
        if (normalized.contains("kết")) {
            return Color.rgb(63, 63, 70);
        }
        return Ui.GREEN;
    }

    private void openPlayer(MediaCard item) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(EXTRA_CARD_PAGE_URL, item.pageUrl);
        intent.putExtra(EXTRA_CARD_TITLE, item.displayTitle());
        intent.putStringArrayListExtra(EXTRA_CARD_FALLBACK_URLS, collectFallbackUrls(item));
        startActivity(intent);
    }

    private ArrayList<String> collectFallbackUrls(MediaCard selectedItem) {
        ArrayList<String> urls = new ArrayList<>();
        if (selectedItem.pageUrl != null && selectedItem.pageUrl.length() > 0) {
            urls.add(selectedItem.pageUrl);
        }

        String targetTitle = selectedItem.displayTitle();
        if (normalizeMatchKey(targetTitle).length() == 0) {
            return urls;
        }

        for (SourceListResult result : sourceCache.values()) {
            for (MediaCard candidate : result.items) {
                if (candidate.pageUrl == null || candidate.pageUrl.length() == 0 || urls.contains(candidate.pageUrl)) {
                    continue;
                }

                if (isSameMatchTitle(targetTitle, candidate.displayTitle())) {
                    urls.add(candidate.pageUrl);
                    if (urls.size() >= 12) {
                        return urls;
                    }
                }
            }
        }
        return urls;
    }

    private boolean isSameMatchTitle(String first, String second) {
        String firstKey = normalizeMatchKey(first);
        String secondKey = normalizeMatchKey(second);
        if (firstKey.length() == 0 || secondKey.length() == 0) {
            return false;
        }
        if (firstKey.equals(secondKey)) {
            return true;
        }

        Set<String> firstTokens = matchTokens(firstKey);
        Set<String> secondTokens = matchTokens(secondKey);
        if (firstTokens.size() < 2 || secondTokens.size() < 2) {
            return false;
        }

        int overlap = 0;
        for (String token : firstTokens) {
            if (secondTokens.contains(token)) {
                overlap++;
            }
        }
        return overlap >= 2 && overlap >= Math.min(firstTokens.size(), secondTokens.size()) / 2;
    }

    private Set<String> matchTokens(String value) {
        Set<String> tokens = new HashSet<>();
        String[] parts = value.split("\\s+");
        for (String part : parts) {
            if (part.length() < 3) {
                continue;
            }
            if ("vs".equals(part) || "women".equals(part) || "woman".equals(part) || "football".equals(part) || "club".equals(part)) {
                continue;
            }
            tokens.add(part);
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
                    renderSources();
                    if (source.id.equals(activeSourceId)) {
                        renderContent();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    loadingSourceIds.remove(source.id);
                    if (source.id.equals(activeSourceId)) {
                        Toast.makeText(this, "Không tải được nguồn: " + error.getMessage(), Toast.LENGTH_LONG).show();
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
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(Ui.dp(this, 28), Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24));
        root.setBackgroundColor(Ui.PANEL);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "Cài đặt nguồn", 28, Ui.TEXT, Typeface.BOLD);
        form.addView(title);
        EditText urlInput = new EditText(this);
        urlInput.setHint("https://movie.example/list");
        urlInput.setSingleLine(true);
        urlInput.setTextColor(Ui.TEXT);
        urlInput.setHintTextColor(Ui.MUTED);
        urlInput.setBackground(Ui.rounded(Ui.CARD, 18, 2, Ui.BORDER, this));
        urlInput.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 64));
        inputParams.setMargins(0, Ui.dp(this, 18), 0, 0);
        form.addView(urlInput, inputParams);

        EditText aliasInput = new EditText(this);
        aliasInput.setHint("Tên hiển thị (không bắt buộc)");
        aliasInput.setSingleLine(true);
        aliasInput.setTextColor(Ui.TEXT);
        aliasInput.setHintTextColor(Ui.MUTED);
        aliasInput.setBackground(Ui.rounded(Ui.CARD, 18, 2, Ui.BORDER, this));
        aliasInput.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        LinearLayout.LayoutParams aliasParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 64));
        aliasParams.setMargins(0, Ui.dp(this, 14), 0, 0);
        form.addView(aliasInput, aliasParams);

        Button addButton = actionButton("Kiểm tra và thêm");
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-2, Ui.dp(this, 58));
        addParams.setMargins(0, Ui.dp(this, 18), 0, 0);
        form.addView(addButton, addParams);

        Button updateButton = actionButton("Kiểm tra cập nhật APK");
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(-2, Ui.dp(this, 58));
        updateParams.setMargins(0, Ui.dp(this, 12), 0, 0);
        form.addView(updateButton, updateParams);

        Button closeButton = actionButton("Đóng");
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-2, Ui.dp(this, 58));
        closeParams.setMargins(0, Ui.dp(this, 12), 0, 0);
        form.addView(closeButton, closeParams);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        TextView listTitle = Ui.text(this, "Nguồn đã lưu", 24, Ui.TEXT, Typeface.BOLD);
        list.addView(listTitle);
        for (MediaSource source : sources) {
            list.addView(savedSourceRow(source, dialog));
        }

        root.addView(form, new LinearLayout.LayoutParams(0, -1, 1));
        ScrollView listScroll = new ScrollView(this);
        listScroll.setFillViewport(false);
        listScroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(0, -1, 1);
        listParams.setMargins(Ui.dp(this, 28), 0, 0, 0);
        root.addView(listScroll, listParams);

        addButton.setOnClickListener(view -> addSourceFromDialog(urlInput, aliasInput, dialog));
        updateButton.setOnClickListener(view -> checkForUpdate());
        closeButton.setOnClickListener(view -> dialog.dismiss());

        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(-1, -1);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        urlInput.requestFocus();
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
        actions.addView(download, new LinearLayout.LayoutParams(Ui.dp(this, 230), Ui.dp(this, 58)));

        Button close = actionButton("Đóng");
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 58));
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

    private View savedSourceRow(MediaSource source, Dialog dialog) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        row.setBackground(Ui.rounded(Ui.CARD, 16, 1, Ui.BORDER, this));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 76));
        rowParams.setMargins(0, Ui.dp(this, 12), 0, 0);
        row.setLayoutParams(rowParams);

        TextView label = Ui.text(this, source.name + "\n" + source.url, 14, Ui.TEXT, Typeface.BOLD);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -1, 1));

        Button remove = actionButton("Xóa");
        remove.setOnClickListener(view -> {
            removeSource(source.id);
            dialog.dismiss();
            showSettingsDialog();
        });
        row.addView(remove, new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 52)));
        return row;
    }

    private void addSourceFromDialog(EditText urlInput, EditText aliasInput, Dialog dialog) {
        String url = urlInput.getText().toString().trim();
        String alias = aliasInput.getText().toString().trim();
        if (url.length() == 0) {
            Toast.makeText(this, "Nhập link nguồn trước.", Toast.LENGTH_SHORT).show();
            return;
        }
        sourceExecutor.execute(() -> {
            try {
                SourceValidationResult result = apiClient.validateSource(url);
                String id = "source-" + System.currentTimeMillis();
                MediaSource source = new MediaSource(id, alias.length() > 0 ? alias : result.sourceName, result.normalizedUrl);
                runOnUiThread(() -> {
                    sources.add(source);
                    sourceStore.writeSources(sources);
                    sourceStore.writeActiveSourceId(source.id);
                    sourceStore.writeCount(source.id, result.itemCount);
                    sourceCounts.put(source.id, result.itemCount);
                    activeSourceId = source.id;
                    dialog.dismiss();
                    renderAll();
                    loadSource(source, true, true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this, "Không thêm được nguồn: " + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void removeSource(String sourceId) {
        List<MediaSource> nextSources = new ArrayList<>();
        for (MediaSource source : sources) {
            if (!source.id.equals(sourceId)) {
                nextSources.add(source);
            }
        }
        sources = nextSources;
        sourceCache.remove(sourceId);
        sourceStore.writeSources(sources);
        sourceStore.removeSourceData(sourceId);
        if (sourceId.equals(activeSourceId)) {
            activeSourceId = sources.isEmpty() ? null : sources.get(0).id;
            sourceStore.writeActiveSourceId(activeSourceId);
        }
        renderAll();
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
        if (activeCategoryKey == null) {
            for (MediaCard item : result.items) {
                if (!item.isEnded()) {
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
        for (MediaCard item : result.items) {
            if (!item.isEnded() && categoryKey.equals(item.sportKey)) {
                count++;
            }
        }
        return count;
    }

    private MediaSource findSource(String sourceId) {
        if (sourceId == null) {
            return null;
        }
        for (MediaSource source : sources) {
            if (source.id.equals(sourceId)) {
                return source;
            }
        }
        return null;
    }
}
