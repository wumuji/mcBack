package com.mcback.ui.theme;

/**
 * 主题变化时需要重新应用颜色的组件实现这个接口。
 *
 * <p>自绘组件(卡片、按钮、标签)在绘制时直接读取当前调色板,因此只需要重绘;
 * 使用 Swing 原生组件的地方则在这里重新设置颜色与字体。</p>
 */
public interface ThemeAware {

    void onThemeChanged();
}
