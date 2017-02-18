package com.nao20010128nao.NukkitDepLoader;

import java.io.File;

/**
 * Created by nao on 2017/02/18.
 */
public interface Plug {
    File getFilePath();
    File getDataFolder();
    Logger getPlugLogger();
    ClassLoader getPlugClassLoader();

    interface Logger{
        void info(String mes);
        void error(String mes);
        void error(String mes,Throwable err);
    }
}
