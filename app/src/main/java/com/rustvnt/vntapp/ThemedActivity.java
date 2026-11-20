package com.rustvnt.vntapp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/**
 * 提供 VNT 的固定配色与编程式控件工厂。
 *
 * <p>界面全部由代码构建，配色以浅色/深色两套常量表达；子类只需要关心页面内容。
 * 深色开关保存在 Activity 私有偏好中，作为唯一事实来源，避免配置与字段不同步。
 */
abstract class ThemedActivity extends AppCompatActivity {
    protected static final int INDIGO = Color.rgb(79, 70, 229);
    protected static final int GREEN = Color.rgb(22, 163, 74);
    protected static final int RED = Color.rgb(220, 38, 38);
    protected static final int AMBER = Color.rgb(217, 119, 6);

    protected boolean dark;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        // 必须在 super.onCreate 之前设置，AppCompat 会直接按目标模式创建主题，
        // 避免先按系统模式构建界面再重建一次。
        dark = getPreferences(MODE_PRIVATE).getBoolean("dark", false);
        AppCompatDelegate.setDefaultNightMode(
                dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        // targetSdk 36 下系统强制 edge-to-edge，显式声明一次以兼容旧版本系统。
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    }

    /**
     * 把系统栏与刘海 inset 转成 View 的 padding。
     *
     * <p>edge-to-edge 下内容会绘制到状态栏/刘海区域，自定义标题栏必须自己让出这段距离，
     * 否则刘海会盖住按钮。padding 是额外量，原有内边距会保留。
     *
     * @param top   是否处理顶部（状态栏 + 刘海）
     * @param left  是否处理左侧（横屏时的刘海或手势区）
     * @param right 是否处理右侧
     * @param bottom 是否处理底部（导航栏 / 手势条）
     */
    protected void applySystemBarPadding(View view, boolean top, boolean left,
                                         boolean right, boolean bottom) {
        applySystemBarPadding(view, top, left, right, bottom, 0);
    }

    /**
     * @param extraTopHeight 固定高度控件（如自定义标题栏）需要额外增加的高度。
     *                       这类控件高度是写死的 dp 值，仅加 padding 会压缩内容，
     *                       因此把 inset 同时加到 LayoutParams.height 上。
     */
    protected void applySystemBarPadding(View view, boolean top, boolean left,
                                         boolean right, boolean bottom, int extraTopHeight) {
        if (view == null) return;
        int baseTop = view.getPaddingTop();
        int baseLeft = view.getPaddingLeft();
        int baseRight = view.getPaddingRight();
        int baseBottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(
                    left ? baseLeft + bars.left : baseLeft,
                    top ? baseTop + bars.top : baseTop,
                    right ? baseRight + bars.right : baseRight,
                    bottom ? baseBottom + bars.bottom : baseBottom);
            if (extraTopHeight > 0) {
                ViewGroup.LayoutParams params = v.getLayoutParams();
                if (params != null) {
                    int height = extraTopHeight + (top ? bars.top : 0);
                    if (params.height != height) {
                        params.height = height;
                        v.setLayoutParams(params);
                    }
                }
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    protected int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    protected LinearLayout.LayoutParams size(int width, int height) {
        return new LinearLayout.LayoutParams(dp(width), dp(height));
    }

    protected LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    protected LinearLayout.LayoutParams weightedWithStart() {
        LinearLayout.LayoutParams params = weighted();
        params.leftMargin = dp(12);
        return params;
    }

    protected LinearLayout.LayoutParams top(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    protected LinearLayout.LayoutParams end(int end) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(end);
        return params;
    }

    protected static int colorWithAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    protected GradientDrawable round(int fill, int radius, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), stroke);
        return drawable;
    }

    protected int bgPage() { return dark ? Color.rgb(2, 6, 23) : Color.rgb(248, 250, 252); }
    protected int bgHeader() { return dark ? Color.rgb(15, 23, 42) : Color.WHITE; }
    protected int bgCard() { return dark ? Color.rgb(15, 23, 42) : Color.WHITE; }
    protected int bgInput() { return dark ? Color.rgb(30, 41, 59) : Color.WHITE; }
    protected int bgChip() { return dark ? Color.rgb(30, 41, 59) : Color.rgb(248, 250, 252); }
    protected int border() { return dark ? Color.rgb(51, 65, 85) : Color.rgb(226, 232, 240); }
    protected int textStrong() { return dark ? Color.WHITE : Color.rgb(15, 23, 42); }
    protected int textBody() { return dark ? Color.rgb(226, 232, 240) : Color.rgb(51, 65, 85); }
    protected int textMuted() { return dark ? Color.rgb(148, 163, 184) : Color.rgb(100, 116, 139); }
    protected int neutralDot() { return dark ? Color.rgb(71, 85, 105) : Color.rgb(203, 213, 225); }

    protected LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        return value;
    }

    protected LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    protected TextView text(String value, float size, boolean bold, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        return text;
    }

    protected LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(round(bgCard(), 12, border(), 1));
        card.setElevation(dp(1));
        return card;
    }

    protected LinearLayout statCard(String label, String value) {
        LinearLayout card = card();
        card.setPadding(dp(15), dp(15), dp(15), dp(15));
        card.addView(text(label, 12, true, textMuted()));
        card.addView(text(value, 23, true, textStrong()), top(7));
        return card;
    }

    protected TextView chip(String value, int foreground, int background) {
        TextView chip = text(value, 11, true, foreground);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(9), dp(5), dp(9), dp(5));
        chip.setBackground(round(background, 8, border(), 1));
        return chip;
    }

    protected TextView labelValue(String label, String value, int valueColor) {
        TextView text = text(label + ":  " + value, 13, false, textMuted());
        // 单个 TextView 让紧凑的移动端卡片更易读；关键值沿用页面强调色。
        if (label.equals("虚拟 IP") || label.equals("网络编号")) text.setTextColor(valueColor);
        return text;
    }

    protected void detailRow(LinearLayout card, String label, String value) {
        TextView labelView = text(label, 13, false, textMuted());
        labelView.setTextIsSelectable(true);
        TextView valueView = text(value, 13, false, textStrong());
        valueView.setTypeface(Typeface.MONOSPACE);
        valueView.setTextIsSelectable(true);
        // 标签固定占 1/3，值在右侧 2/3 区域内右对齐、过长自动换行
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        row.addView(labelView, weighted());
        valueView.setGravity(Gravity.END);
        row.addView(valueView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        card.addView(row, top(4));
        View divider = new View(this);
        divider.setBackgroundColor(border());
        card.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    protected View featureChip(String label, boolean enabled) {
        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(10), dp(8), dp(10), dp(8));
        item.setBackground(round(bgChip(), 8, 0, 0));
        View dot = new View(this);
        dot.setBackground(round(enabled ? GREEN : neutralDot(), 4, 0, 0));
        LinearLayout.LayoutParams dotParams = size(8, 8);
        dotParams.rightMargin = dp(8);
        item.addView(dot, dotParams);
        item.addView(text(label, 12, false, textBody()));
        return item;
    }

    protected Button primary(String value) { return styledButton(value, INDIGO, Color.WHITE, 0); }
    protected Button danger(String value) { return styledButton(value, RED, Color.WHITE, 0); }
    protected Button ghost(String value) { return styledButton(value, bgInput(), textBody(), border()); }

    protected Button styledButton(String value, int background, int foreground, int stroke) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(13);
        button.setTextColor(foreground);
        button.setAllCaps(false);
        button.setMinHeight(dp(40));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(round(background, 9, stroke, stroke == 0 ? 0 : 1));
        return button;
    }

    protected Button iconButton(String value) {
        Button button = ghost(value);
        button.setTextSize(20);
        button.setPadding(0, 0, 0, 0);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    protected ImageButton imageButton(int drawable, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(drawable);
        button.setContentDescription(description);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setMinimumWidth(dp(40));
        button.setMinimumHeight(dp(40));
        button.setBackground(round(bgInput(), 9, border(), 1));
        return button;
    }

    protected ImageButton plainImageButton(int drawable, String description) {
        ImageButton button = imageButton(drawable, description);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    // ---- 配置表单控件 ----

    protected EditText field(LinearLayout form, String label, String value, boolean password,
                             String hint, String help) {
        fieldLabel(form, label, help);
        EditText field = input(value, password, hint);
        form.addView(field, top(8));
        return field;
    }

    protected LinearLayout fieldLabel(LinearLayout form, String label, String help) {
        LinearLayout labelRow = row();
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        labelRow.addView(text(label, 12, true, textBody()), weighted());
        labelRow.addView(configHelpButton(label, help), size(32, 32));
        form.addView(labelRow, top(12));
        return labelRow;
    }

    protected EditText input(String value, boolean password, String hint) {
        EditText field = new EditText(this);
        field.setText(value);
        if (hint != null) field.setHint(hint);
        field.setTextSize(14);
        field.setTextColor(textStrong());
        field.setHintTextColor(textMuted());
        field.setSingleLine(true);
        if (password) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        field.setPadding(dp(12), dp(9), dp(12), dp(9));
        field.setBackground(round(bgInput(), 9, border(), 1));
        return field;
    }

    /** 可折叠配置分组，返回分组内容容器。 */
    protected LinearLayout section(LinearLayout form, String title, boolean expanded) {
        LinearLayout wrapper = column();
        wrapper.setBackground(round(bgCard(), 12, border(), 1));
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(13), dp(14), dp(13));
        header.addView(text(title, 15, true, textStrong()), weighted());
        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_down);
        chevron.setColorFilter(textMuted());
        chevron.setPadding(dp(6), dp(6), dp(6), dp(6));
        chevron.setRotation(expanded ? 180f : 0f);
        header.addView(chevron, size(32, 32));
        LinearLayout body = column();
        body.setPadding(dp(14), 0, dp(14), dp(14));
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        header.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(show ? 180f : 0f).setDuration(200).start();
        });
        wrapper.addView(header);
        wrapper.addView(body);
        form.addView(wrapper, top(12));
        return body;
    }

    protected void notice(LinearLayout form, String value, int color) {
        TextView notice = text(value, 11, false, dark ? Color.rgb(253, 230, 138) : Color.rgb(146, 64, 14));
        notice.setPadding(dp(11), dp(9), dp(11), dp(9));
        notice.setBackground(round(colorWithAlpha(color, dark ? 34 : 20),
                8, colorWithAlpha(color, 100), 1));
        form.addView(notice, top(9));
    }

    /** 与输入框同风格的下拉框适配器：文字配色与内边距跟随当前主题。 */
    protected ArrayAdapter<String> spinnerAdapter(String[] items) {
        return new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextSize(14);
                view.setTextColor(textStrong());
                view.setSingleLine(true);
                view.setGravity(Gravity.CENTER_VERTICAL);
                view.setPadding(dp(14), 0, dp(36), 0);
                view.setBackgroundColor(Color.TRANSPARENT);
                return view;
            }

            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextSize(14);
                view.setTextColor(textStrong());
                view.setSingleLine(true);
                view.setGravity(Gravity.CENTER_VERTICAL);
                view.setPadding(dp(14), dp(12), dp(14), dp(12));
                return view;
            }
        };
    }

    /** 圆角边框容器 + 右侧箭头，替代 Spinner 默认样式。 */
    protected FrameLayout spinnerBox(Spinner spinner) {
        FrameLayout box = new FrameLayout(this);
        box.setBackground(round(bgInput(), 9, border(), 1));
        spinner.setBackgroundColor(Color.TRANSPARENT);
        spinner.setPopupBackgroundDrawable(round(bgHeader(), 9, border(), 1));
        box.addView(spinner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        TextView arrow = text("▾", 13, false, textMuted());
        FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        arrowParams.rightMargin = dp(14);
        box.addView(arrow, arrowParams);
        return box;
    }

    protected CheckBox toggle(LinearLayout form, String title, String description,
                              String help, boolean checked) {
        LinearLayout rowLayout = row();
        rowLayout.setGravity(Gravity.CENTER_VERTICAL);
        rowLayout.setPadding(dp(12), dp(10), dp(12), dp(10));
        rowLayout.setBackground(round(bgInput(), 9, border(), 1));
        LinearLayout texts = column();
        texts.addView(text(title, 14, true, textStrong()));
        texts.addView(text(description, 11, false, textMuted()), top(3));
        rowLayout.addView(texts, weighted());
        rowLayout.addView(configHelpButton(title, help), size(32, 32));
        CheckBox box = new CheckBox(this);
        box.setChecked(checked);
        rowLayout.addView(box);
        form.addView(rowLayout, top(9));
        return box;
    }

    protected ImageButton configHelpButton(String title, String help) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_help_outline);
        button.setColorFilter(textMuted());
        button.setContentDescription(title + "帮助");
        button.setPadding(dp(7), dp(7), dp(7), dp(7));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title).setMessage(help).setPositiveButton("知道了", null).show());
        return button;
    }

    protected static List<String> toList(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i).trim();
                if (!value.isEmpty()) values.add(value);
            }
        }
        return values;
    }

    protected void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /** 动态列表输入：每行一个输入框，带删除按钮，底部有添加按钮。 */
    protected final class ListInput {
        /** 输入内容变化时的附加提示，例如服务器地址解析出的协议。 */
        interface Observer {
            void onChanged(String joinedValue);
        }

        private final String hint;
        private final LinearLayout rows;
        private final List<EditText> inputs = new ArrayList<>();
        private Observer observer;

        protected ListInput(LinearLayout form, String label, String hint, String addLabel,
                            List<String> initial, String help) {
            this.hint = hint;
            fieldLabel(form, label, help);
            rows = column();
            form.addView(rows);
            for (String value : initial) addRow(value);
            if (initial.isEmpty()) addRow("");
            Button add = ghost(addLabel);
            add.setOnClickListener(v -> addRow(""));
            form.addView(add, top(8));
        }

        protected void setObserver(Observer observer) {
            this.observer = observer;
            notifyObserver();
        }

        private void notifyObserver() {
            if (observer != null) observer.onChanged(joined());
        }

        private void addRow(String value) {
            LinearLayout rowLayout = row();
            rowLayout.setGravity(Gravity.CENTER_VERTICAL);
            EditText field = input(value, false, hint);
            field.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(android.text.Editable s) { notifyObserver(); }
            });
            rowLayout.addView(field, weighted());
            Button remove = ghost("✕");
            remove.setTextColor(RED);
            remove.setOnClickListener(v -> {
                rows.removeView(rowLayout);
                inputs.remove(field);
                notifyObserver();
            });
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(44), dp(44));
            removeParams.leftMargin = dp(8);
            rowLayout.addView(remove, removeParams);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = dp(8);
            rows.addView(rowLayout, rowParams);
            inputs.add(field);
            notifyObserver();
        }

        protected String joined() {
            StringBuilder value = new StringBuilder();
            for (EditText field : inputs) {
                String item = field.getText().toString().trim();
                if (item.isEmpty()) continue;
                if (value.length() > 0) value.append('\n');
                value.append(item);
            }
            return value.toString();
        }
    }

    protected Context context() {
        return this;
    }
}
