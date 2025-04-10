package com.kolaps;

import fr.lip6.move.pnml.framework.general.PnmlExport;
import fr.lip6.move.pnml.framework.utils.ModelRepository;
import fr.lip6.move.pnml.framework.utils.exception.*;
import fr.lip6.move.pnml.pnmlcoremodel.hlapi.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class PetriNetBuilder
{
    PetriNetDocHLAPI doc;
    PetriNetHLAPI net;
    PageHLAPI page;
    Map<String, PlaceHLAPI> places = new HashMap<>();
    PlaceHLAPI lastPlace;

    PetriNetBuilder() throws InvalidIDException, VoidRepositoryException {
        ModelRepository.getInstance().createDocumentWorkspace("void");
        this.doc = new PetriNetDocHLAPI();
        this.net = new PetriNetHLAPI("net", PNTypeHLAPI.COREMODEL, new NameHLAPI("DeadlockFind"), doc);
        this.page = new PageHLAPI("toppage", new NameHLAPI("toppage"), null, net);
        PlaceHLAPI startPoint = new PlaceHLAPI("start");
        startPoint.setContainerPageHLAPI(this.page);
        this.places.put("start",startPoint);
        this.lastPlace = startPoint;
    }

    public static void buildTestNet() throws InvalidIDException, VoidRepositoryException, OtherException, ValidationFailedException, BadFileFormatException, IOException, OCLValidationFailed, UnhandledNetType {
        ModelRepository.getInstance().createDocumentWorkspace("void");

        PetriNetDocHLAPI doc = new PetriNetDocHLAPI();
        PetriNetHLAPI net = new PetriNetHLAPI("net", PNTypeHLAPI.COREMODEL, new NameHLAPI("DeadlockFind"), doc);
        PageHLAPI page = new PageHLAPI("toppage", new NameHLAPI("toppage"), null, net); //use of "null" is authorized but not encouraged


        PlaceHLAPI p1 = new PlaceHLAPI("place1");
        PlaceHLAPI p2 = new PlaceHLAPI("place2");
        PlaceHLAPI p3 = new PlaceHLAPI("place3");


        TransitionHLAPI t1 = new TransitionHLAPI("transistion1");
        TransitionHLAPI t2 = new TransitionHLAPI("transistion2");

        new ArcHLAPI("a1", p1, t1, page);
        new ArcHLAPI("a3", p3, t2, page);
        new ArcHLAPI("a4", t2, p2, page);
        new ArcHLAPI("a5", t1, p3, page);

        p1.setContainerPageHLAPI(page);
        p2.setContainerPageHLAPI(page);
        p3.setContainerPageHLAPI(page);
        t1.setContainerPageHLAPI(page);
        t2.setContainerPageHLAPI(page);


        PnmlExport pex = new PnmlExport();
        pex.exportObject(doc, System.getenv("fpath") + "/exporttest.pnml");

    }
    public void exportToPnml() throws OtherException, ValidationFailedException, BadFileFormatException, IOException, OCLValidationFailed, UnhandledNetType {
        PnmlExport pex = new PnmlExport();
        pex.exportObject(this.doc, System.getenv("fpath") + "/exporttest.pnml");
    }

    public void addNewThreadPlace()
    {

    }
}
