package org.easydarwin.easypusher;

import android.databinding.DataBindingUtil;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.URLSpan;
import android.view.Menu;
import android.view.MenuItem;

import org.easydarwin.easypusher.databinding.ActivityAboutBinding;

/**
 * 关于我们
 * */
public class AboutActivity extends AppCompatActivity implements Toolbar.OnMenuItemClickListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityAboutBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_about);

        setSupportActionBar(binding.mainToolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        binding.mainToolbar.setOnMenuItemClickListener(this);
        // 左边的小箭头（注意需要在setSupportActionBar(toolbar)之后才有效果）
        binding.mainToolbar.setNavigationIcon(R.drawable.com_back);

        binding.version.setText("EasyPusher Android 推流器");
        binding.version.append("(");

        SpannableString spannableString;
        if (EasyApplication.activeDays >= 9999) {
            spannableString = new SpannableString("激活码永久有效");
            spannableString.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorGREEN)),
                    0,
                    spannableString.length(),
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        } else if (EasyApplication.activeDays > 0) {
            spannableString = new SpannableString(String.format("激活码还剩%d天可用", EasyApplication.activeDays));
            spannableString.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorYELLOW)),
                    0,
                    spannableString.length(),
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        } else {
            spannableString = new SpannableString(String.format("激活码已过期(%d)", EasyApplication.activeDays));
            spannableString.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorRED)),
                    0,
                    spannableString.length(),
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        }

        binding.version.append(spannableString);
        binding.version.append(")");

//        binding.serverTitle.setText("-EasyDarwin RTSP流媒体服务器：\n");
//        binding.serverTitle.setMovementMethod(LinkMovementMethod.getInstance());
//
//        spannableString = new SpannableString("http://www.easydarwin.org");
//        //设置下划线文字
//        spannableString.setSpan(new URLSpan("http://www.easydarwin.org"), 0, spannableString.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
//
//        //设置文字的前景色
//        spannableString.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorTheme)),
//                0,
//                spannableString.length(),
//                Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
//
//        binding.serverTitle.append(spannableString);
//
//        binding.playerTitle.setText("-EasyPlayerPro全功能播放器：\n");
//        binding.playerTitle.setMovementMethod(LinkMovementMethod.getInstance());
//        spannableString = new SpannableString("https://github.com/EasyDSS/EasyPlayerPro");
//        //设置下划线文字
//        spannableString.setSpan(new URLSpan("https://github.com/EasyDSS/EasyPlayerPro"), 0, spannableString.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
//
//        //设置文字的前景色
//        spannableString.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorTheme)),
//                0,
//                spannableString.length(),
//                Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
//
//        binding.playerTitle.append(spannableString);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    @Override
    public boolean onMenuItemClick(MenuItem menuItem) {
        return false;
    }

    // 返回的功能
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}