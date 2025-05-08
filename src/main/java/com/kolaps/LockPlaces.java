package com.kolaps;

import fr.lip6.move.pnml.ptnet.hlapi.PTMarkingHLAPI;
import fr.lip6.move.pnml.ptnet.hlapi.PlaceHLAPI;

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
        public PlaceHLAPI getLock() {
            return lock;
        }

        public PlaceHLAPI getWait() {
            return wait;
        }

        public PlaceHLAPI getNotify() {
            return notify;
        }

        PlaceHLAPI lock;
        PlaceHLAPI wait;
        PlaceHLAPI notify;


    }

    private final Map<String, PlaceTriple> places;


    public LockPlaces() {
        this.places = new HashMap<>();
    }

    public PlaceTriple getPlace(String varName) {
        PlaceTriple triple = places.computeIfAbsent(varName, key -> {return new PlaceTriple();});

        triple.lock = createPlaceForType(varName, PlaceType.LOCK);
        triple.wait = createPlaceForType(varName, PlaceType.WAIT);
        triple.notify = createPlaceForType(varName, PlaceType.NOTIFY);
        return triple;
    }

    private PlaceHLAPI createPlaceForType(String varName, PlaceType type) {
        System.out.println("Creating new " + type + " Place for identifier: " + varName);
        String placeName = type.name() + "_" + varName;
        placeName = escapeXml(placeName);
        PlaceHLAPI place = createPlace(placeName, PetriNetBuilder.getMainPage());
        System.out.println("Created " + type + " Place: " + place.getId() + " (ID: " + varName + ")");
        return place;
    }

}
