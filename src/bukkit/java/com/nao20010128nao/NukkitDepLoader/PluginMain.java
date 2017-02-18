package com.nao20010128nao.NukkitDepLoader;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Created by nao on 2017/02/17.
 */
public class PluginMain extends JavaPlugin implements Plug,Plug.Logger{
    @Override
    public void onLoad() {
        LibrariesLoader.doLoad(this);
    }

    @Override
    public File getFilePath() {
        return getDataFolder().getParentFile();
    }

    @Override
    public ClassLoader getPlugClassLoader() {
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
        getLogger().warning(mes);
    }

    @Override
    public void error(String mes, Throwable err) {
        getLogger().warning(()->{
            try(StringWriter sw=new StringWriter()){
                try(PrintWriter pw=new PrintWriter(sw)){
                    sw.write(mes);
                    if(err!=null)
                        err.printStackTrace(pw);
                }
                return sw.toString();
            }catch (IOException e){
                return null;
            }
        });
    }
}

