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
import java.util.Map;

public class PTExtension {

    private static ArrayList<String> inhibitorArcs = new ArrayList<>();

    public static void addInhibitorArcs() throws FileNotFoundException {
        String filePath = "example.pnml";
        String targetSource = "p-614C-28EF-3";
        String targetTarget = "t-614C-28F3-6";

        // Пространство имён PNML
        String namespace = "http://www.pnml.org/version-2009/grammar/pnml";

        // Чтение XML-файла через Axiom
        FileInputStream fis = new FileInputStream(filePath);
        XMLInputFactory inputFactory = XMLInputFactory.newInstance();
        //StAXOMBuilder builder = new StAXOMBuilder(inputFactory, fis);
        OMXMLParserWrapper builder = OMXMLBuilderFactory.createOMBuilder(fis);
        OMElement root = builder.getDocumentElement();

        // Поиск всех элементов arc
        for (Iterator<?> it = root.getDescendants(true); it.hasNext(); ) {
            Object next = it.next();
            if (next instanceof OMElement) {
                OMElement elem = (OMElement) next;
                if ("arc".equals(elem.getLocalName()) &&
                        namespace.equals(elem.getNamespace().getNamespaceURI())) {

                    String source = elem.getAttributeValue(new QName("source"));
                    String target = elem.getAttributeValue(new QName("target"));

                    if (targetSource.equals(source) && targetTarget.equals(target)) {
                        // Проверяем, нет ли уже <type>
                        boolean hasType = false;
                        for (Iterator<?> childIt = elem.getChildElements(); childIt.hasNext(); ) {
                            OMElement child = (OMElement) childIt.next();
                            if ("type".equals(child.getLocalName())) {
                                hasType = true;
                                break;
                            }
                        }
                        if (!hasType) {
                            // Создаём и добавляем <type value="inhibitor"/>
                            OMFactory factory = elem.getOMFactory();
                            OMElement type = factory.createOMElement("type", null);
                            type.addAttribute("value", "inhibitor", null);
                            elem.addChild(type);
                            System.out.println("Добавлен <type value=\"inhibitor\"/> к arc " + elem.getAttributeValue(new QName("id")));
                        }
                    }
                }
            }
        }

        // Сохранение изменений
        try (FileOutputStream fos = new FileOutputStream("example_modified.pnml")) {
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
