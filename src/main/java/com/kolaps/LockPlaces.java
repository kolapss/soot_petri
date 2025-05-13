package com.kolaps;

import fr.lip6.move.pnml.ptnet.hlapi.PTMarkingHLAPI;
import fr.lip6.move.pnml.ptnet.hlapi.PlaceHLAPI;
import soot.Local;

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

        private final String varName; // Store varName to use it for creation

        // Constructor for PlaceTriple, takes the varName
        public PlaceTriple(String varName) {
            this.varName = varName;
            // lock, wait, notify are implicitly null here
        }

        public synchronized PlaceHLAPI getLock() {
            if (lock == null) {
                // Call the outer class's method to create the place
                // LockPlaces.this syntax is used to refer to the outer class instance
                lock = LockPlaces.this.createPlaceForType(varName, PlaceType.LOCK);
            }
            return lock;
        }

        public synchronized PlaceHLAPI getWait() {
            if (wait == null) {
                wait = LockPlaces.this.createPlaceForType(varName, PlaceType.WAIT);
            }
            return wait;
        }

        public synchronized PlaceHLAPI getNotify() {
            if (notify == null) {
                notify = LockPlaces.this.createPlaceForType(varName, PlaceType.NOTIFY);
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


    private PlaceHLAPI createPlaceForType(String varName, PlaceType type) {
        System.out.println("Creating new " + type + " Place for identifier: " + varName);
        String placeName = type.name() + "_" + varName;
        placeName = escapeXml(placeName);
        PlaceHLAPI place = createPlace(placeName, PetriNetBuilder.getMainPage());
        if(type == PlaceType.LOCK) {
            new PTMarkingHLAPI(Long.valueOf(1),place);
        }
        System.out.println("Created " + type + " Place: " + place.getId() + " (for varName: " + varName + ")");
        return place;
    }
}
