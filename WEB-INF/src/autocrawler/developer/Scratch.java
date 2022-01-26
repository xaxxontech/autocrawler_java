package autocrawler.developer;


import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;


public class Scratch {


    public void regexp() {
        Pattern quality = Pattern.compile("^\\s*asdf asdf");
        Matcher mat;
        mat = quality.matcher("fdsaf"); //    /^\s*Quality=/
        if (mat.find()) {
            mat.find();
        }

        System.out.println("192.168.0.107".substring(0,4));

        if ("Gableem".matches(".*ei.*"))
            System.out.println("match");

        System.out.println("ok here we go");
        System.out.println("192.168.0.107".replaceFirst("\\.\\d+\\.\\d+$", ""));
    }



    public static long newID() {
        return System.nanoTime();
    }

    public static void main(String[] args) {
//        new Scratch().regexp();

        ArrayList myList = new ArrayList();
        myList.add("asdf");
        myList.add(null);
        System.out.println(myList.size());
        String[] asdf = {"asdf", "fdsa", "sdfgg"};
        if (asdf.length == 0)
            asdf = null;

//        try {
//            File d = new File("/home/auto/ros2_ws/");
//            String[] pathnames = d.list();
//            for (String pathname : pathnames) {
//                // Print the names of files and directories
//                System.out.println(pathname);
//            }
//
//
//        } catch (Exception e) { e.printStackTrace(); }


    }
}

