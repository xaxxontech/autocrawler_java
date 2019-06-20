package developer;


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

    public double voltsComp(double n) {
        double volts = 12.0; // default
        final double nominalvolts = 12.0;
        final double exponent = 1.6;

        if (state.exists(State.values.batteryvolts.toString())) {
            if (Math.abs(state.getDouble(State.values.batteryvolts.toString()) - volts) > 2.5) // sanity check
                Util.log("error state:battvolts beyond expected range! "+state.get(State.values.batteryvolts), this);
            else  volts = Double.parseDouble(state.get(State.values.batteryvolts));
        }

        n = n * Math.pow(nominalvolts/volts, exponent);
        return n;
    }

    public static void main(String[] args) {
//        new Scratch().regexp();

        String zork = String.valueOf(9-3);

        System.out.println(zork);


    }
}

