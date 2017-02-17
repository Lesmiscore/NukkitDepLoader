package com.nao20010128nao.NukkitDepLoader;

import cn.nukkit.Server;
import cn.nukkit.plugin.PluginBase;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by nao on 2017/02/17.
 */
public class PluginMain extends PluginBase {
    @Override
    public void onEnable() {
        ClassLoader system=Server.class.getClassLoader();
        if(!(system instanceof URLClassLoader)){
            getLogger().error("Unsupported ClassLoader detected.");
            return;
        }
        try {
            getLogger().info("Loading jar/zips inside libs...");
            new File(getServer().getFilePath(),"libs").mkdirs();
            for (File f:new File(getServer().getFilePath(),"libs").listFiles()){
                addClasspath(system,f.toURI().toURL());
            }
        } catch (Throwable e) {
            getLogger().error("An error occurred while loading",e);
        }
        getLogger().info("Checking Maven...");
        File mavenList=new File(getServer().getFilePath(),"maven.list");
        if(mavenList.exists()){
            Set<Dependency> deps=new HashSet<>();
            Set<Repository> repos=new HashSet<>();
            repos.add(new Repository("http://central.maven.org/maven2/"));//Maven Central
            try {
                Files.readAllLines(mavenList.toPath()).forEach(s->{
                    if(s.startsWith("#")){
                        //comment
                    }if(s.startsWith("repo;")){
                        repos.add(new Repository(s.substring(5)));
                    }else{
                        deps.add(new Dependency(s));
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
            getLogger().info("Resolving dependencies...");
            deps.forEach(d->{
                repos.forEach(r->{
                    getLogger().info("Resolving "+d+" at "+r);
                    if(r.checkFileExist(d.getRelativePathForPom())){
                        d.foundAt.add(r);
                    }else if(r.checkFileExist(d.getRelativePathForJar())){
                        d.foundAt.add(r);
                    }
                });
            });
            Set<Dependency> filtered=deps.stream().filter(d->!d.foundAt.isEmpty()).collect(Collectors.toSet());
            deps.clear();
            deps.addAll(filtered);
            List<Set<Dependency>> added=new ArrayList<>();
            added.add(deps);

            getLogger().info("Resolving dependencies recursively...");
            Set<Dependency> checked=new HashSet<>();
            while(!added.get(added.size()-1).isEmpty()){
                Set<Dependency> newAdded=new HashSet<>();
                Set<Dependency> previousAdded=added.get(added.size()-1);
                previousAdded.forEach(d->{
                    if(checked.contains(d)){
                        return;
                    }
                    d.foundAt.forEach(r->{
                        if(!r.checkFileExist(d.getRelativePathForPom())){
                            return;
                        }
                        getLogger().info("Loading POM for "+d+" at "+r);
                        try {
                            byte[] data=r.download(d.getRelativePathForPom());
                            if(data==null)return;
                            merge(newAdded,pomToDeps(data,r,repos));
                        } catch (Throwable e) {
                            getLogger().error("An error occurred while loading POM file",e);
                        }
                    });
                    checked.add(d);
                });
                added.add(newAdded);
            }

            getLogger().info("Downloading artifacts...");
            added.stream().skip(1).forEach(s->merge(deps,s));
            Set<File> using=new HashSet<>();
            File dirToDownload=new File(getDataFolder(),"maven");
            dirToDownload.mkdirs();

            deps.forEach(d->{
                getLogger().info("Download: "+d);
                List<Repository> downloadFrom=new ArrayList<>();
                d.foundAt.forEach(r->{
                    if(r.checkFileExist(d.getRelativePathForJar())){
                        downloadFrom.add(r);
                    }
                });
                if(downloadFrom.isEmpty()){
                    getLogger().error("Failed: "+d+" : No repository has artifact for it");
                    return;
                }

                UUID fileUUID=UUID.randomUUID();
                File dest=new File(dirToDownload,fileUUID+".jar");
                for(Repository r:downloadFrom){
                    try {
                        Files.write(dest.toPath(),Objects.requireNonNull(r.download(d.getRelativePathForJar())));
                        using.add(dest);
                        break;
                    } catch (IOException e) {
                        getLogger().error("Failed: "+d,e);
                    }
                }
            });
            getLogger().info("Loading jars...");
            using.forEach(f->{
                f.deleteOnExit();
                try {
                    addClasspath(system,f.toURI().toURL());
                } catch (Throwable e) {
                }
            });
        }else{
            try {
                Files.createFile(mavenList.toPath());
            } catch (IOException e) {
            }
        }
    }

    public static void merge(Set<Dependency> a,Collection<Dependency> b) {
        b.forEach(d-> merge(a,d));
    }

    public static void merge(Set<Dependency> deps,Dependency dep){
        boolean ok=false;
        for(Dependency d:new HashSet<>(deps)){
            if(d.equals(dep)){
                d.foundAt.addAll(dep.foundAt);
                Dependency latestVersion=newerVersion(d,dep);
                if(latestVersion!=null){
                    deps.remove(d);
                    latestVersion.foundAt.addAll(d.foundAt);
                    deps.add(latestVersion);
                }
                ok=true;
            }
        }
        if(ok){
            return;
        }
        deps.add(resolveVersion(dep));
    }

    public static Dependency newerVersion(Dependency a,Dependency b){
        if(a==b){return a;}
        if(!Objects.equals(a.group,b.group)){
            return null;//cannot be compared: different group
        }
        if(!Objects.equals(a.artifact,b.artifact)){
            return null;//cannot be compared: different artifact
        }
        if(!Objects.equals(a.classifier,b.classifier)){
            return null;//cannot be compared: different classifier
        }

        String metaRelative = a.group + "/" + a.artifact;
        metaRelative = metaRelative.replace('.', '/');
        metaRelative += "/maven-metadata.xml";
        byte[] data=null;
        for(Repository r: Stream.concat(a.foundAt.stream(),b.foundAt.stream()).collect(Collectors.toSet())){
            data = r.download(metaRelative);
            if(data!=null)break;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = factory.newDocumentBuilder();
            Document document = documentBuilder.parse(new ByteArrayInputStream(data));

            List<Node> versioning = NodeListToArrayList(document.getDocumentElement().getElementsByTagName("versioning"));
            if (!versioning.isEmpty()) {
                List<Node> versions = NodeListToArrayList(((Element) versioning.get(0)).getElementsByTagName("versions"));
                if (!versions.isEmpty()) {
                    List<String> vers=new ArrayList<>(versions.stream().map(Node::getTextContent).collect(Collectors.toList()));
                    Collections.reverse(vers);
                    if(vers.indexOf(a.version)>vers.indexOf(b.version)){
                        return a;
                    }else{
                        return b;
                    }
                }
            }
        } catch (Throwable e) {
        }
        if(a.version==null&b.version==null){
            return a;//null version
        }
        if(a.version!=null){
            return a;//use a instead
        }else{
            return b;//use b instead
        }
    }

    public static Dependency resolveVersion(Dependency in){
        if(in.version==null||"null".equals(in.version)||in.version.isEmpty()||in.version.equals("RELEASE")) {
            // require release version
            String metaRelative = in.group + "/" + in.artifact;
            metaRelative = metaRelative.replace('.', '/');
            metaRelative += "/maven-metadata.xml";
            byte[] data=null;
            for(Repository r:in.foundAt){
                data = r.download(metaRelative);
                if(data!=null)break;
            }
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder documentBuilder = factory.newDocumentBuilder();
                Document document = documentBuilder.parse(new ByteArrayInputStream(data));

                List<Node> versioning = NodeListToArrayList(document.getDocumentElement().getElementsByTagName("versioning"));
                if (!versioning.isEmpty()) {
                    List<Node> release = NodeListToArrayList(((Element) versioning.get(0)).getElementsByTagName("release"));
                    if (!release.isEmpty()) {
                        return new Dependency(in.group, in.artifact, release.get(0).getTextContent(), in.classifier,in.foundAt);
                    }
                }
            } catch (Throwable e) {
                return in;
            }
        }else if(in.version.equals("LATEST")){
            String metaRelative = in.group + "/" + in.artifact;
            metaRelative = metaRelative.replace('.', '/');
            metaRelative += "/maven-metadata.xml";
            byte[] data=null;
            for(Repository r:in.foundAt){
                data = r.download(metaRelative);
                if(data!=null)break;
            }
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder documentBuilder = factory.newDocumentBuilder();
                Document document = documentBuilder.parse(new ByteArrayInputStream(data));

                List<Node> versioning = NodeListToArrayList(document.getDocumentElement().getElementsByTagName("versioning"));
                if (!versioning.isEmpty()) {
                    List<Node> latest = NodeListToArrayList(((Element) versioning.get(0)).getElementsByTagName("release"));
                    if (!latest.isEmpty()) {
                        return new Dependency(in.group, in.artifact, latest.get(0).getTextContent(), in.classifier,in.foundAt);
                    }
                }
            } catch (Throwable e) {
                return in;
            }
        }else if(in.version.matches("^\\[^[,]+\\]$")){
            Pattern regex=Pattern.compile("^\\[(^[,]+)\\]$");
            Matcher matcher=regex.matcher(in.version);
            if(matcher.find()){
                return new Dependency(in.group, in.artifact, matcher.group(), in.classifier,in.foundAt);
            }
        }else if((in.version.startsWith("(")|in.version.startsWith("["))|(in.version.endsWith(")")|in.version.endsWith("]"))){
            // I dont know how
            return resolveVersion(new Dependency(in.group, in.artifact, "LATEST", in.classifier,in.foundAt));
        }
        return in;
    }

    public static Set<Dependency> pomToDeps(byte[] in,Repository r,Set<Repository> repos)throws Throwable{
        Set<Dependency> result=new HashSet<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = factory.newDocumentBuilder();
        Document document = documentBuilder.parse(new ByteArrayInputStream(in));

        try {
            ArrayList<Node> repositories=NodeListToArrayList(document.getDocumentElement().getElementsByTagName("repositories"));
            if(!repositories.isEmpty()){
                NodeListToArrayList(repositories.get(0).getChildNodes()).forEach(repoNode->{
                    if (repoNode.getNodeName().equals("repository")) {
                        repos.add(new Repository(((Element)repoNode).getElementsByTagName("url").item(0).getTextContent()));
                    }
                });
            }
        } catch (Exception e) {
            // prevent failing while finding repositories because it is not so important
        }

        NodeList depNodeList=document.getDocumentElement().getElementsByTagName("dependencies");
        if(depNodeList.getLength()!=0){
            Node dependencies=depNodeList.item(0);
            ArrayList<Node> depNodes=NodeListToArrayList(dependencies.getChildNodes());
            depNodes.forEach(depNode->{
                try {
                    if (depNode.getNodeName().equals("dependency")) {
                        Dependency dep=new Dependency(
                                NodeListToArrayList(depNode.getChildNodes())
                                        .stream()
                                        .collect(
                                                (Supplier<HashMap<String, String>>) HashMap::new,
                                                (a,b)->a.put(b.getNodeName(), b.getTextContent()),
                                                HashMap::putAll
                                        )
                        );
                        dep=resolveVersion(dep);
                        dep.foundAt.add(r);
                        merge(result,dep);
                    }
                } catch (Exception e) {
                }
            });
        }
        return result;
    }

    public static ArrayList<Node> NodeListToArrayList(NodeList list){
        ArrayList<Node> nodes=new ArrayList<>();
        for(int i=0; i < list.getLength(); i++) {
            nodes.add(list.item(i));
        }
        return nodes;
    }


    static class Dependency{
        public final String group,artifact,version,classifier;
        //public final Map<String,String> config=new HashMap<>();//reserved
        public final Set<Repository> foundAt=new HashSet<>();

        public Dependency(String in){
            String[] at=in.toLowerCase().split("@");
            String[] names=at[0].split(":");
            group=names[0];
            artifact=names[1];
            version=names[2];
            if(names.length==4){
                classifier=names[3];
            }else{
                classifier=null;
            }
        }

        public Dependency(Map<String,String> tags){
            if("test".equals(tags.get("scope"))){
                throw new RuntimeException("\"test\" scope dependency is not accepted");
            }
            if("provided".equals(tags.get("scope"))){
                throw new RuntimeException("\"provided\" scope dependency is not accepted");
            }
            if("system".equals(tags.get("scope"))){
                throw new RuntimeException("\"system\" scope dependency is not accepted");
            }
            if("import".equals(tags.get("import"))){
                throw new RuntimeException("\"system\" scope dependency is not accepted");
            }
            this.group=tags.get("groupId");
            this.artifact=tags.get("artifactId");
            this.version=tags.get("version");
            this.classifier=null;
        }

        public Dependency(String group,String artifact,String version,String classifier,Set<Repository> foundAt){
            this.group=group;
            this.artifact=artifact;
            this.version=version;
            this.classifier=classifier;
            if(foundAt!=null){
                this.foundAt.addAll(foundAt);
            }
        }

        public String getRelativePathForJar(){
            String result=group+"/"+artifact;
            result=result.replace('.','/');
            result+="/"+version+"/"+artifact+"-"+version;
            if(classifier!=null){
                result+="-"+classifier;
            }
            result+=".jar";
            return result;
        }

        public String getRelativePathForPom(){
            String result=group+"/"+artifact;
            result=result.replace('.','/');
            result+="/"+version+"/"+artifact+"-"+version+".pom";
            return result;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(new Object[]{group,artifact,version,classifier});
        }

        @Override
        public String toString() {
            return String.join(":",group,artifact,version,classifier);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Dependency &&
                    Objects.equals(group, ((Dependency) obj).group) &&
                    Objects.equals(artifact, ((Dependency) obj).artifact) &&
                    ((Objects.equals(version, null) | Objects.equals(((Dependency) obj).version, null)) ||
                            Objects.equals(version, ((Dependency) obj).version)) &&
                    Objects.equals(classifier, ((Dependency) obj).classifier);
        }
    }

    static class Repository{
        public final String root;
        public Repository(String r){
            if(r.endsWith("/")){
                r=r.substring(0,r.length()-1);
            }
            root=r;
        }
        public boolean checkFileExist(String relativePath){
            try {
                HttpURLConnection con=(HttpURLConnection) new URL(root+"/"+relativePath).openConnection();
                con.setRequestMethod("HEAD");
                con.getInputStream().close();
                return true;
            } catch (IOException e) {
                return download(relativePath)!=null;
            }
        }
        public byte[] download(String relativePath){
            try {
                HttpURLConnection con=(HttpURLConnection) new URL(root+"/"+relativePath).openConnection();
                con.setRequestMethod("GET");
                ByteArrayOutputStream baos=new ByteArrayOutputStream();
                byte[] buffer=new byte[2048];
                while(true){
                    int r=con.getInputStream().read(buffer);
                    if(r<=0){
                        return baos.toByteArray();
                    }
                    baos.write(buffer,0,r);
                }
            } catch (IOException e) {
                return null;
            }
        }


        @Override
        public int hashCode() {
            return root.hashCode();
        }

        @Override
        public String toString() {
            return root;
        }
    }

    static void addClasspath(ClassLoader loader,URL url) throws Throwable{
        Method addURL=loader.getClass().getDeclaredMethod("addURL",URL.class);
        addURL.setAccessible(true);
        addURL.invoke(loader,url);
    }
}
