package dev.local.sunwindowdemo.features.suggestions;

import org.junit.Test;
import static org.junit.Assert.*;

public class SuggestionRulesTest {
    @Test public void codesPreserveLeadingZerosWithoutTreatingPhoneNumbersAsCodes(){
        assertEquals("001234",SuggestionRules.otp("【示例服务】2026年您的验证码为001234，请勿泄露。",false));
        assertEquals("000321",SuggestionRules.otp("Your verification code is 000321",false));
        assertEquals("001234",SuggestionRules.otp("验证码：００１２３４",true));
        for(String bare:new String[]{"1234","001234","12345678","００１２３４","hello 123456"})
            assertNull(bare,SuggestionRules.otp(bare,true));
        assertEquals("987654",SuggestionRules.otp("987654是您的验证码",false));
        assertNull(SuggestionRules.otp("订单 001234 已送达",false));
        assertNull(SuggestionRules.otp("验证码服务电话 13800138000",false));
        assertNull(SuggestionRules.otp("001234",false));
    }
    @Test public void shareRoutingPreservesOriginalUrlAndOnlyMatchesRealDomains(){
        var bili=SuggestionRules.link("分享视频 https://b23.tv/AbC123，快来看看");
        assertEquals("tv.danmaku.bili",bili.packageName());
        assertEquals("https://b23.tv/AbC123",bili.value());assertFalse(bili.token());
        assertEquals("com.taobao.taobao",SuggestionRules.link("https://detail.tmall.com/item.htm?id=123").packageName());
        assertNull(SuggestionRules.link("https://taobao.com.example.org/item"));
        assertNull(SuggestionRules.link("https://nottaobao.com/item"));
        assertEquals("https://v.douyin.com/Example/",SuggestionRules.link("复制 https://v.douyin.com/Example/ 打开抖音").value());
    }
    @Test public void tokensAreLaunchHintsRatherThanInventedProductUrls(){
        var token=SuggestionRules.link("￥AbCd1234EfGh￥");
        assertTrue(token.token());assertEquals("￥AbCd1234EfGh￥",token.value());
        assertEquals("com.taobao.taobao",token.packageName());
    }
    @Test public void mapSearchIncludesLandmarksAndPreservesMapShareLinks(){
        assertEquals("杭州西湖",SuggestionRules.address("杭州西湖"));
        assertEquals("星光商场",SuggestionRules.address("星光商场"));
        assertEquals("北京市朝阳区建国路88号",SuggestionRules.address("北京市朝阳区建国路88号"));
        assertEquals("杭州市西湖区文三路100号",SuggestionRules.address("杭州市西湖区文三路100号"));
        for(String address:new String[]{"221B Baker Street, London","1600 Amphitheatre Parkway","123 Main St","10 Downing Street"})
            assertEquals(address,SuggestionRules.address(address));
        for(String phrase:new String[]{"hello","world","hello world","test","随便聊聊","版本号","版本号123456","订单号","订单号123456","公司代码","公司代码123456","请打开门","请到星光商场集合","street","Main Street"})
            assertNull(phrase,SuggestionRules.address(phrase));
        assertNull(SuggestionRules.address("001234"));assertNull(SuggestionRules.address("https://example.org/a"));
        String link="https://uri.amap.com/marker?position=120.1,30.2";
        assertEquals(link,SuggestionRules.address(link));assertEquals("com.autonavi.minimap",SuggestionRules.mapOwner(link));
        assertNull(SuggestionRules.mapOwner("https://amap.com.example.org/marker"));
        assertEquals(4096,SuggestionRules.bounded("x".repeat(6000)).length());
    }
}
