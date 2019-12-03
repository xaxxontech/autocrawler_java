package developer;


import java.util.regex.Matcher;
import java.util.regex.Pattern;
import autocrawler.State;



public class Scratch {

    protected static State state = State.getReference();
    static Process proc;

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
            ProcessBuilder p = new ProcessBuilder("xcalc");
            p.start();
        } catch (Exception e) {}


        System.out.println("true");


    }
}

