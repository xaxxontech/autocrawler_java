package autocrawler.developer;


import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


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

        try {
            String[] cmd = {"/bin/bash", "-c", "roscd autocrawler ; pwd"};
            Process proc = Runtime.getRuntime().exec(cmd);
            BufferedReader procReader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String str = procReader.readLine();
            System.out.println(str);

        } catch (Exception e) { e.printStackTrace(); }


    }
}

