package dev.local.sunwindowdemo;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class AppList {
    static void fill(Context c, LinearLayout box, List<AppCatalog.App> all, Consumer<AppCatalog.App> chosen) {
        EditText search = new EditText(c); search.setSingleLine(true); search.setHint("搜索应用");
        search.setTextColor(Color.WHITE); search.setHintTextColor(0xffaaaaaa);
        box.addView(search, new LinearLayout.LayoutParams(-1, -2));
        GridView list = new GridView(c);list.setNumColumns(4);
        int gap=(int)(4*c.getResources().getDisplayMetrics().density);list.setHorizontalSpacing(gap);list.setVerticalSpacing(gap);
        ArrayList<AppCatalog.App> visible = new ArrayList<>(all);
        BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return visible.size(); }
            public Object getItem(int i) { return visible.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View recycled, ViewGroup parent) {
                LinearLayout row = new LinearLayout(c);row.setOrientation(LinearLayout.VERTICAL); row.setGravity(17); row.setPadding(gap, gap*2, gap, gap*2);
                ImageView icon = new ImageView(c); icon.setImageDrawable(visible.get(i).icon);
                int size = (int)(40*c.getResources().getDisplayMetrics().density);
                row.addView(icon, new LinearLayout.LayoutParams(size, size));
                TextView label = new TextView(c); label.setText(visible.get(i).label); label.setTextSize(12); label.setTextColor(Color.WHITE);label.setGravity(17);label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END); label.setPadding(0,gap,0,0);
                row.addView(label); return row;
            }
        };
        list.setAdapter(adapter); list.setOnItemClickListener((p,v,i,id) -> chosen.accept(visible.get(i)));
        box.addView(list, new LinearLayout.LayoutParams(-1,0,1));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int st,int count,int after) {}
            public void onTextChanged(CharSequence s,int st,int before,int count) {
                String q=s.toString().toLowerCase(Locale.ROOT); visible.clear();
                for(AppCatalog.App a:all) if(a.label.toLowerCase(Locale.ROOT).contains(q)||a.pkg.toLowerCase(Locale.ROOT).contains(q)) visible.add(a);
                adapter.notifyDataSetChanged();
            }
            public void afterTextChanged(Editable e) {}
        });
    }
}
