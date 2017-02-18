package com.nao20010128nao.NukkitDepLoader;

import cn.nukkit.plugin.JavaPluginLoader;
import cn.nukkit.plugin.PluginBase;

import java.io.File;

/**
 * Created by nao on 2017/02/17.
 */
public class PluginMain extends PluginBase implements Plug,Plug.Logger{
    @Override
    public void onLoad() {
        LibrariesLoader.doLoad(this);
    }

    @Override
    public File getFilePath() {
        return new File(getServer().getFilePath());
    }

    @Override
    public ClassLoader getClassLoader() {
        return JavaPluginLoader.class.getClassLoader();
    }

    @Override
    public Logger getPlugLogger() {
        return this;
    }

    @Override
    public void info(String mes) {
        getLogger().info(mes);
    }

    @Override
    public void error(String mes) {
        getLogger().error(mes);
    }

    @Override
    public void error(String mes, Throwable err) {
        getLogger().error(mes,err);
    }
}
