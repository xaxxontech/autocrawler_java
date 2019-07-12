package developer;


import oculusPrime.Settings;
import oculusPrime.Util;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import oculusPrime.State;



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


        ArrayList <String> str = new ArrayList<>();
        str.add("asdf");

        System.out.println(str.get(0));


    }
}

