package com.kolaps;

import fr.lip6.move.pnml.framework.general.PnmlExport;
import fr.lip6.move.pnml.framework.utils.ModelRepository;
import fr.lip6.move.pnml.framework.utils.exception.*;
import fr.lip6.move.pnml.ptnet.hlapi.*;

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

    PetriNetBuilder() throws InvalidIDException, VoidRepositoryException, OtherException, ValidationFailedException, BadFileFormatException, IOException, OCLValidationFailed, UnhandledNetType {
        ModelRepository.getInstance().createDocumentWorkspace("void");
        this.doc = new PetriNetDocHLAPI();
        this.net = new PetriNetHLAPI("net", PNTypeHLAPI.COREMODEL, new NameHLAPI("DeadlockFind"), doc);
        this.page = new PageHLAPI("toppage", new NameHLAPI("toppage"), null, net);
        PlaceHLAPI startPoint = new PlaceHLAPI("start");
        startPoint.setContainerPageHLAPI(this.page);
        this.places.put("start",startPoint);
        this.lastPlace = startPoint;

    }

    /*public void buildTestNet() throws InvalidIDException, VoidRepositoryException, OtherException, ValidationFailedException, BadFileFormatException, IOException, OCLValidationFailed, UnhandledNetType {



        PlaceHLAPI p1 = new PlaceHLAPI("place1");
        PlaceHLAPI p2 = new PlaceHLAPI("place2");
        PlaceHLAPI p3 = new PlaceHLAPI("place3");
        PlaceHLAPI p4 = new PlaceHLAPI("place4");


        TransitionHLAPI t1 = new TransitionHLAPI("transistion1");
        TransitionHLAPI t2 = new TransitionHLAPI("transistion2");

        new ArcHLAPI("a1", p1, t1, page);
        new ArcHLAPI("a2", p2, t1, page);
        new ArcHLAPI("a3", t1, p3, page);
        new ArcHLAPI("a4", p4, t2, page);
        new ArcHLAPI("a5", t2, p1, page);


        p1.setContainerPageHLAPI(page);
        p2.setContainerPageHLAPI(page);
        p3.setContainerPageHLAPI(page);
        t1.setContainerPageHLAPI(page);
        t2.setContainerPageHLAPI(page);
        p4.setContainerPageHLAPI(page);

        PTMarkingHLAPI ptMarking = new PTMarkingHLAPI(Long.valueOf(1),p4);
        //PTMarkingHLAPI pt1Marking = new PTMarkingHLAPI(Long.valueOf(1),p2);



        PnmlExport pex = new PnmlExport();
        pex.exportObject(doc, System.getenv("fpath") + "/exporttest.pnml");

    }*/
    public void exportToPnml() throws OtherException, ValidationFailedException, BadFileFormatException, IOException, OCLValidationFailed, UnhandledNetType {
        PnmlExport pex = new PnmlExport();
        pex.exportObject(this.doc, System.getenv("fpath") + "/exporttest.pnml");
    }


}
