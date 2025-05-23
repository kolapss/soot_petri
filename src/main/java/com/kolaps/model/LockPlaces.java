package com.kolaps.model;

import com.kolaps.PetriNetBuilder;
import fr.lip6.move.pnml.ptnet.hlapi.PTMarkingHLAPI;
import fr.lip6.move.pnml.ptnet.hlapi.PlaceHLAPI;
import soot.Local;
import soot.SootMethod;
import soot.Unit;

import java.util.HashMap;
import java.util.Map;

import static com.kolaps.PetriNetBuilder.escapeXml;
import static com.kolaps.PetriNetModeler.createPlace;

public class LockPlaces {
    public enum PlaceType {
        LOCK,
        WAIT,
        NOTIFY
    }

    public class PlaceTriple {
        // Fields are now private and will be lazily initialized
        private PlaceHLAPI lock;
        private PlaceHLAPI wait;
        private PlaceHLAPI notify;

        public Local getVar() {
            return var;
        }

        public void setVar(Local var) {
            this.var = var;
        }

        private Local var;

        public String getVarName() {
            return varName;
        }

        private final String varName; // Store varName to use it for creation

        // Constructor for PlaceTriple, takes the varName
        public PlaceTriple(String varName) {
            this.varName = varName;
            // lock, wait, notify are implicitly null here
        }

        public synchronized PlaceHLAPI getLock(Unit unit, SootMethod method) {
            if (lock == null) {
                // Call the outer class's method to create the place
                // LockPlaces.this syntax is used to refer to the outer class instance
                lock = LockPlaces.this.createPlaceForType(varName, PlaceType.LOCK,unit, method);
            }
            return lock;
        }

        public synchronized PlaceHLAPI getWait(Unit unit, SootMethod method) {
            if (wait == null) {
                wait = LockPlaces.this.createPlaceForType(varName, PlaceType.WAIT,unit, method);
            }
            return wait;
        }

        public synchronized PlaceHLAPI getNotify(Unit unit, SootMethod method) {
            if (notify == null) {
                notify = LockPlaces.this.createPlaceForType(varName, PlaceType.NOTIFY,unit, method);
            }
            return notify;
        }
    }

    public Map<String, PlaceTriple> getPlaces() {
        return places;
    }

    private final Map<String, PlaceTriple> places;

    public LockPlaces() {
        this.places = new HashMap<>();
    }

    public PlaceTriple getPlace(String varName) {
        return places.computeIfAbsent(varName, key -> new PlaceTriple(key));
    }


    private PlaceHLAPI createPlaceForType(String varName, PlaceType type, Unit unit, SootMethod method) {
        System.out.println("Creating new " + type + " Place for identifier: " + varName);
        String placeName = type.name() + "_" + varName;
        placeName = escapeXml(placeName);
        PlaceHLAPI place = createPlace(placeName, PetriNetBuilder.getMainPage(),unit, method);
        if(type == PlaceType.LOCK) {
            new PTMarkingHLAPI(Long.valueOf(1),place);
        }
        System.out.println("Created " + type + " Place: " + place.getId() + " (for varName: " + varName + ")");
        return place;
    }
}
