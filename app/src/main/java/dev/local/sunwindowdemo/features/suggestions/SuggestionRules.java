package dev.local.sunwindowdemo.features.suggestions;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local bounded recognition only. Never resolves share tokens or sends clipboard text online. */
public final class SuggestionRules {
    private SuggestionRules() {}
    public record Link(String packageName,String value,boolean token) {}
    private record App(String pkg,String[] domains,String[] tokens) {}
    private static final App[] APPS={
        new App("com.taobao.taobao",new String[]{"taobao.com","tmall.com","tb.cn"},new String[]{"淘口令","淘宝","天猫"}),
        new App("com.jingdong.app.mall",new String[]{"jd.com","jd.cn","3.cn"},new String[]{"京东"}),
        new App("com.xunmeng.pinduoduo",new String[]{"pinduoduo.com","yangkeduo.com"},new String[]{"拼多多"}),
        new App("com.ss.android.ugc.aweme",new String[]{"douyin.com","iesdouyin.com"},new String[]{"抖音"}),
        new App("com.smile.gifmaker",new String[]{"kuaishou.com","gifshow.com"},new String[]{"快手"}),
        new App("tv.danmaku.bili",new String[]{"bilibili.com","b23.tv"},new String[]{}),
        new App("com.xingin.xhs",new String[]{"xiaohongshu.com","xhslink.com"},new String[]{"小红书"}),
        new App("com.zhihu.android",new String[]{"zhihu.com","zhihu.link"},new String[]{}),
        new App("com.sina.weibo",new String[]{"weibo.com","weibo.cn","t.cn"},new String[]{}),
        new App("com.sankuai.meituan",new String[]{"meituan.com","meituan.net","dpurl.cn","dianping.com"},new String[]{"美团"})
    };
    private static final Pattern URL=Pattern.compile("https?://[^\\s<>\\\"\\u3000，。；！？、（）【】]+",Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE=Pattern.compile("(?<![0-9])([0-9]{4,8})(?![0-9])");
    private static final Pattern KEYWORD=Pattern.compile("验证码|校验码|动态码|动态密码|一次性密码|verification\\s*code|one[- ]time|\\botp\\b",Pattern.CASE_INSENSITIVE);

    public static String bounded(String text) { return text==null?"":text.substring(0,Math.min(4096,text.length())).strip(); }
    public static String otp(String text,boolean clipboard) {
        // Normalize full-width digits without losing a leading zero.
        StringBuilder normalized=new StringBuilder();
        for(char ch:bounded(text).toCharArray())normalized.append(ch>='０'&&ch<='９'?(char)('0'+ch-'０'):ch);
        String value=normalized.toString();
        Matcher words=KEYWORD.matcher(value);String result=null;int best=Integer.MAX_VALUE;
        while(words.find()){
            Matcher codes=CODE.matcher(value);
            while(codes.find()){
                int distance=codes.start()>=words.end()?codes.start()-words.end():words.start()-codes.end();
                int score=distance+(codes.start()<words.start()?16:0);
                if(distance>=0&&distance<=32&&score<best){best=score;result=codes.group(1);}
            }
        }
        return result;
    }
    public static String host(String url) {
        try {String host=URI.create(url).getHost();return host==null?"":host.toLowerCase(Locale.ROOT);}catch(Exception ignored){return "";}
    }
    public static boolean domain(String host,String suffix){return host.equals(suffix)||host.endsWith("."+suffix);}
    public static String firstUrl(String text) {
        Matcher m=URL.matcher(bounded(text));
        if(!m.find())return null;
        return m.group().replaceAll("[)）\\]】>,，。；;！!？?]+$","");
    }
    public static Link link(String text) {
        String bounded=bounded(text);Matcher urls=URL.matcher(bounded);
        while(urls.find()){
            String url=urls.group().replaceAll("[)）\\]】>,，。；;！!？?]+$","");String host=host(url);
            for(App app:APPS)for(String suffix:app.domains)if(domain(host,suffix))return new Link(app.pkg,url,false);
        }
        if(firstUrl(bounded)!=null)return null;
        for(App app:APPS)for(String word:app.tokens)
            if(bounded.contains(word)&&bounded.length()>word.length()+4)return new Link(app.pkg,bounded,true);
        // A bare Taobao share token is deliberately left for the target app to interpret.
        if(bounded.matches("(?s).*[¥￥₤$][A-Za-z0-9]{8,24}[¥￥₤$].*"))return new Link("com.taobao.taobao",bounded,true);
        return null;
    }
    public static String mapOwner(String text) {
        String url=firstUrl(text);if(url==null)return null;String h=host(url);
        if(domain(h,"amap.com"))return "com.autonavi.minimap";
        if(domain(h,"map.baidu.com")||domain(h,"map.n.shifen.com"))return "com.baidu.BaiduMap";
        if(domain(h,"map.qq.com"))return "com.tencent.map";
        if(domain(h,"maps.google.com")||domain(h,"maps.app.goo.gl")||domain(h,"maps.google.cn"))return "com.google.android.apps.maps";
        return null;
    }
    public static String address(String text) {
        String value=bounded(text);
        if(mapOwner(value)!=null)return value;
        if(value.length()<2||value.length()>120||value.contains("\n")||firstUrl(value)!=null
                ||value.matches("[0-9\\s+.,-]+")||otp(value,true)!=null)return null;
        // Require a local place/street structure, not just a short clipboard phrase.
        if(value.matches("[\\p{IsHan}]{2,}(?:[省市区县镇乡村路街巷店站园厦馆湾桥山湖岛院楼]|广场|中心|商场|公园|景点|学校|大学|医院|机场|酒店|餐厅|小区|公司)(?:[0-9]+(?:号|栋|单元|室|楼|层|门))*")
                ||value.matches("(?i)\\d+[a-z]?[- ]+[a-z][a-z .'-]*\\s(?:street|st|road|rd|avenue|ave|lane|ln|drive|dr|boulevard|blvd|way|court|ct|place|pl|square|sq|parkway|pkwy)\\b.*"))return value;
        return null;
    }
}
