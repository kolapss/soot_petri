package com.kolaps;

import fr.lip6.move.pnml.framework.utils.exception.InvalidIDException;
import fr.lip6.move.pnml.framework.utils.exception.VoidRepositoryException;
import fr.lip6.move.pnml.hlpn.hlcorestructure.HlcorestructureFactory;
import fr.lip6.move.pnml.hlpn.hlcorestructure.PnObject;
import fr.lip6.move.pnml.hlpn.hlcorestructure.ToolInfo;
import fr.lip6.move.pnml.ptnet.Arc;
import fr.lip6.move.pnml.ptnet.hlapi.ToolInfoHLAPI;
import fr.lip6.move.pnml.ptnet.hlapi.*;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

public class PetriNetModeler {

    private static int transitionCounter = 0;
    private static int placeCounter = 0;
    private  static int arcCounter = 0;
    public static ArcHLAPI createArc(PlaceHLAPI source, TransitionHLAPI target, PageHLAPI page) {
        String id = "arc_" + source.getId() + "_" + target.getId()+arcCounter++;
        ArcHLAPI arc = null; // Вес дуги = 1.0 (обычно)
        try {
            arc = new ArcHLAPI(id, source, target, page);
        } catch (InvalidIDException | VoidRepositoryException e) {
            throw new RuntimeException(e);
        }
        arc.setContainerPageHLAPI(page);
        System.out.println("Created Arc: " + source.getId() + " -> " + target.getId());
        return arc;
    }

    public static ArcHLAPI createArc(TransitionHLAPI source, PlaceHLAPI target, PageHLAPI page) {
        String id = "arc_" + source.getId() + "_" + target.getId();
        ArcHLAPI arc = null;
        try {
            arc = new ArcHLAPI(id, source, target, page);
        } catch (InvalidIDException | VoidRepositoryException e) {
            throw new RuntimeException(e);
        }
        arc.setContainerPageHLAPI(page);
        System.out.println("Created Arc: " + source.getId() + " -> " + target.getId());
        return arc;
    }

    public static TransitionHLAPI createTransition(String baseName, PageHLAPI page) {
        String id = "t" + transitionCounter++;
        NameHLAPI name = new NameHLAPI(baseName + "_" + id);
        NodeGraphicsHLAPI graphics = null; // Создать графику
        TransitionHLAPI transition = null;
        try {
            transition = new TransitionHLAPI(id, name, graphics, page);
        } catch (InvalidIDException e) {
            throw new RuntimeException(e);
        } catch (VoidRepositoryException e) {
            throw new RuntimeException(e);
        }
        transition.setContainerPageHLAPI(page); // Добавить переход на страницу
        System.out.println("Created Transition: " + name.getText() + " on Page " + page.getId());
        return transition;
    }

    public static PlaceHLAPI createPlace(String baseName, PageHLAPI page) {
        String id = "p" + placeCounter++;
        String name = baseName + "_" + id;
        NodeGraphicsHLAPI graphics = null; // Создать графику по необходимости
        PlaceHLAPI place = null;
        try {
            place = new PlaceHLAPI(name, page);
        } catch (InvalidIDException | VoidRepositoryException e) {
            throw new RuntimeException(e);
        }
        // Установка начальной маркировки (если нужно, по умолчанию 0)
        // place.setInitialMarking(...);
        place.setContainerPageHLAPI(page);
        System.out.println("Created Place: " + name + " on Page " + page.getId());
        return place;
    }

}
