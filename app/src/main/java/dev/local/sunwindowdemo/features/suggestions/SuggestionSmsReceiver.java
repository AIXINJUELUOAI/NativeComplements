package dev.local.sunwindowdemo.features.suggestions;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/** RECEIVE_SMS is requested by the settings page. SMS reception/storage remains native. */
public final class SuggestionSmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){
        if(!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction()))return;
        android.os.Bundle config=SuggestionStore.read(c);
        if(!config.getBoolean("enabled")||!config.getBoolean("otp"))return;
        try{
            StringBuilder body=new StringBuilder();
            for(SmsMessage message:Telephony.Sms.Intents.getMessagesFromIntent(intent)){
                if(message.getMessageBody()!=null)body.append(message.getMessageBody());
                if(body.length()>4096)return;
            }
            if(SuggestionRules.otp(body.toString(),false)==null)return;
            c.sendBroadcast(new Intent(SuggestionStore.SMS).setPackage("com.android.systemui")
                .putExtra("body",body.toString()),null,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        }catch(RuntimeException ignored){/* No message bodies or codes in logs. */}
    }
}
