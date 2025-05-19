package com.kolaps;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.OMXMLBuilderFactory;
import org.apache.axiom.om.OMXMLParserWrapper;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;

public class PTExtension {

    private static ArrayList<String> inhibitorArcs = new ArrayList<>();

    public static void modifyPnml() throws FileNotFoundException {
        String filePath = Options.INSTANCE.getStringOption("app.pnml_file","");
        //String targetId = inhibitorArcs.get(0); // <-- укажи нужный id arc

        String namespace = "http://www.pnml.org/version-2009/grammar/pnml";

        FileInputStream fis = new FileInputStream(filePath);
        XMLInputFactory inputFactory = XMLInputFactory.newInstance();
        OMXMLParserWrapper builder = OMXMLBuilderFactory.createOMBuilder(fis);
        OMElement root = builder.getDocumentElement();


        for(String targetId: inhibitorArcs) {
            // Поиск arc по id
            for (Iterator<?> it = root.getDescendants(true); it.hasNext(); ) {
                Object next = it.next();
                if (next instanceof OMElement) {
                    OMElement elem = (OMElement) next;
                    if ("arc".equals(elem.getLocalName()) &&
                            namespace.equals(elem.getNamespace().getNamespaceURI())) {

                        String id = elem.getAttributeValue(new QName("id"));
                        if (targetId.equals(PetriNetBuilder.escapeXml(id))) {
                            // Проверка, есть ли уже <type>
                            boolean hasType = false;
                            for (Iterator<?> childIt = elem.getChildElements(); childIt.hasNext(); ) {
                                OMElement child = (OMElement) childIt.next();
                                if ("type".equals(child.getLocalName())) {
                                    hasType = true;
                                    break;
                                }
                            }

                            if (!hasType) {
                                // Добавляем <type value="inhibitor"/>
                                OMFactory factory = elem.getOMFactory();
                                OMElement type = factory.createOMElement("type", null);
                                type.addAttribute("value", "inhibitor", null);
                                elem.addChild(type);
                                System.out.println("Добавлен <type value=\"inhibitor\"/> в arc с id=" + id);
                            } else {
                                System.out.println("Элемент <type> уже существует в arc с id=" + id);
                            }
                        }
                    }
                }
            }
        }

        // Сохранение изменений
        try (FileOutputStream fos = new FileOutputStream("example.pnml")) {
            root.serialize(fos);
        } catch (IOException | XMLStreamException e) {
            throw new RuntimeException(e);
        }

        System.out.println("Файл успешно обновлён.");
    }


    public static void addInhibitorArc(String inhibitorArc) {
        PTExtension.inhibitorArcs.add(inhibitorArc);
    }
}
