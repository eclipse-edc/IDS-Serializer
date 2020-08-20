package de.fraunhofer.iais.eis.ids.jsonld;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import de.fraunhofer.iais.eis.util.PlainLiteral;
import de.fraunhofer.iais.eis.util.RdfResource;
import de.fraunhofer.iais.eis.util.TypedLiteral;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.ErrorHandlerFactory;
import org.apache.jena.util.FileUtils;
import org.topbraid.spin.util.JenaUtil;

import javax.validation.constraints.NotNull;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.Duration;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;


//TODO: To create TypedLiterals (and PlainLiterals), we are creating a dependency to the whole java libraries. Can we improve on that?
public class MessageParser {

    private static Model ontologyModel = null;

    public static boolean downloadOntology = false;

    private static MessageParser instance;

    private MessageParser()
    { }

    public static MessageParser getInstance()
    {
        if(instance == null)
        {
            instance = new MessageParser();
        }
        return instance;
    }

    private <T> T handleObject(Model inputModel, String objectUri, Class<T> targetClass) throws IOException {
        try {
            //T returnObject = (T) targetClass.getConstructor().setAccessible(true).newInstance();

            Constructor<T> constructor = targetClass.getDeclaredConstructor();
            constructor.setAccessible(true);

            T returnObject = constructor.newInstance();

/*            Field[] fields = returnObject.getClass().getDeclaredFields();
            System.out.println("FIELDS:");
            for(Field field : fields)
            {
                System.out.println(field.getName());
            }
 */

            //Get methods
            Method[] methods = returnObject.getClass().getDeclaredMethods();

            //Store methods in map. Key is the name of the RDF property without ids prefix
            Map<String, Method> methodMap = new HashMap<>();

            Arrays.stream(methods).filter(method -> {
                String name = method.getName();
                //Filter out irrelevant methods
                return name.startsWith("set") && !name.equals("setProperty") && !name.equals("setComment") && !name.equals("setLabel") && !name.equals("setId");
            }).forEach(method -> {
                //Remove "set" part
                String reducedName = method.getName().substring(3);

                //Turn first character to lower case
                char[] c = reducedName.toCharArray();
                c[0] = Character.toLowerCase(c[0]);
                String finalName = new String(c);
                methodMap.put(finalName, method);

            });

            List<String> groupByKeys = new ArrayList<>();

            StringBuilder queryStringBuilder = new StringBuilder();
            queryStringBuilder.append("PREFIX ids: <https://w3id.org/idsa/core/>\nSELECT");
            methodMap.forEach((key1, value) -> {
                //Is the return type some sort of List?
                if(Collection.class.isAssignableFrom(value.getParameterTypes()[0]))
                {
                    //Yes, it is assignable multiple times. Concatenate multiple values together using some delimiter
                    //TODO: What kind of delimiter would be appropriate here?
                    queryStringBuilder.append(" (GROUP_CONCAT(?").append(key1).append(";separator=\"|\") AS ?").append(key1).append("s) ");

                }
                else {
                    //System.out.println("Collection is not assignable from " + value.getParameterTypes()[0]);
                    //No, it's not a list. No need to aggregate
                    queryStringBuilder.append(" ?").append(key1);
                    //We will have to GROUP BY this variable though...
                    groupByKeys.add(key1);
                }
            });
            queryStringBuilder.append(" { ");


            for(Map.Entry<String, Method> entry : methodMap.entrySet())
            {
                //Is this a field which is annotated by NOT NULL?
                boolean nullable = !targetClass.getDeclaredField("_" + entry.getKey()).isAnnotationPresent(NotNull.class);

                //If it is "nullable", we need to make this optional
                if(nullable)
                {
                    queryStringBuilder.append(" OPTIONAL {");
                }
                queryStringBuilder.append(" <").append(objectUri).append("> ") //subject, as passed to the function
                        .append("ids:").append(entry.getKey()) //predicate
                        .append(" ?").append(entry.getKey()).append(" ."); //object
                if(nullable)
                {
                    queryStringBuilder.append("} ");
                }
            }


            queryStringBuilder.append(" } ");

            //Do we need to group? We do, if there is at least one property which can occur multiple times
            //We added all those properties, which may only occur once, to the groupByKeys list
            if(groupByKeys.size() < methodMap.size())
            {
                queryStringBuilder.append("GROUP BY");
                for(String key : groupByKeys)
                {
                    queryStringBuilder.append(" ?").append(key);
                }
            }

            String queryString = queryStringBuilder.toString();

            System.out.println(queryString);

            //Copy the ontology inputModel
            Model combinedModel = ontologyModel;

            //Add the message inputModel to it - prevents additional parsing of the message
            //TODO: Is this even needed?! Maybe we can save some time here. We probably don't need the ontology at all, as we extract subclasses from Jackson
            combinedModel.add(inputModel);

            Query query = QueryFactory.create(queryString);

            //Evaluate query on combined model
            QueryExecution queryExecution = QueryExecutionFactory.create(query, combinedModel);
            ResultSet resultSet = queryExecution.execSelect();


            if(!resultSet.hasNext())
            {
                return returnObject;
                //TODO: throw exception? What about objects which are allowed to be totally empty, such as catalogs?
            }

            //TODO: how does something like "key" : { "@value" : "uri" } behave? Is this a "complex object"?

            while(resultSet.hasNext())
            {
                //TODO: There should be no more than one occurrence. Do some check?
                QuerySolution querySolution = resultSet.next();
                for(Map.Entry<String, Method> entry : methodMap.entrySet())
                {

                    Class<?> currentType = entry.getValue().getParameterTypes()[0];
                    //Is this a field which is annotated by NOT NULL?
                    //boolean nullable = !targetClass.getDeclaredField("_" + entry.getKey()).isAnnotationPresent(NotNull.class);

                    String sparqlParameterName = entry.getKey();
                    if(Collection.class.isAssignableFrom(currentType)) {
                        sparqlParameterName += "s"; //plural form for the concatenated values
                    }
                    if(querySolution.contains(sparqlParameterName))
                    {
                        String currentSparqlBinding = querySolution.get(sparqlParameterName).toString();
                        //There is a binding. If it is a complex sub-object, we need to recursively call this function
                        if(Collection.class.isAssignableFrom(currentType))
                        {
                            //We are working with ArrayLists.
                            //Here, we need to work with the GenericParameterTypes instead to find out what kind of ArrayList we are dealing with
                            if(isArrayListTypePrimitive(entry.getValue().getGenericParameterTypes()[0]))
                            {
                                ArrayList list = new ArrayList();
                                //TODO convert to int/float/...
                                list.addAll(Arrays.asList(currentSparqlBinding.split(" ")));
                                entry.getValue().invoke(returnObject, list);
                            }
                            else
                            {
                                //TODO: foreach object in current binding, add result to some ArrayList, give it to: entry.getValue().invoke()
                            }
                        }
                        else {
                            //Our implementation of checking for primitives (i.e. also includes URLs, Strings, XMLGregorianCalendars, ...
                            if (isPrimitive(currentType)) {

                                //Java way of checking for primitives, i.e. int, char, float, double, ...
                                if(currentType.isPrimitive())
                                {
                                    //Is it REALLY a primitive, as of a Java primitive? (Above, we also considered things like String, URI, ... to be "primitive")
                                    //If it is an actual primitive, there is no need to instantiate anything. Just give it to the function
                                    if(currentType.getSimpleName().equals("int"))
                                    {
                                        entry.getValue().invoke(returnObject, Integer.parseInt(currentSparqlBinding));
                                    }
                                    else if(currentType.getSimpleName().equals("boolean"))
                                    {
                                        entry.getValue().invoke(returnObject, Boolean.parseBoolean(currentSparqlBinding));
                                    }
                                    //TODO: long, short, float, double, byte
                                    entry.getValue().invoke(returnObject, currentSparqlBinding);
                                    System.out.println(entry.getValue().getParameterTypes()[0].getName() + " is a Java primitive");
                                    continue;
                                }

                                System.out.println(entry.getValue().getParameterTypes()[0].getName() + " is some other (rather) primitive value");

                                //Check for the more complex literals

                                //URI
                                if(URI.class.isAssignableFrom(currentType))
                                {
                                    entry.getValue().invoke(returnObject, new URI(currentSparqlBinding));
                                    continue;
                                }

                                //String
                                if(String.class.isAssignableFrom(currentType))
                                {
                                    entry.getValue().invoke(returnObject, currentSparqlBinding);
                                    continue;
                                }

                                //XMLGregorianCalendar
                                if(XMLGregorianCalendar.class.isAssignableFrom(currentType))
                                {
                                    entry.getValue().invoke(returnObject, DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar.from(ZonedDateTime.parse(querySolution.get(sparqlParameterName).asLiteral().getValue().toString()))));
                                    continue;
                                }

                                //TypedLiteral
                                if(TypedLiteral.class.isAssignableFrom(currentType))
                                {
                                    entry.getValue().invoke(returnObject, new TypedLiteral(currentSparqlBinding));
                                    continue;
                                }

                                if(PlainLiteral.class.isAssignableFrom(currentType))
                                {
                                    entry.getValue().invoke(returnObject, new PlainLiteral(currentSparqlBinding));
                                    continue;
                                }

                                //BigInteger
                                if(BigInteger.class.isAssignableFrom(currentType))
                                {
                                    entry.getValue().invoke(returnObject, new BigInteger(currentSparqlBinding));
                                    continue;
                                }

                                //byte[]
                                if(byte[].class.isAssignableFrom(currentType))
                                {
                                    entry.getValue().invoke(returnObject, currentSparqlBinding.getBytes());
                                    continue;
                                }

                                //Duration
                                if(Duration.class.isAssignableFrom(currentType))
                                {
                                    entry.getValue().invoke(returnObject, DatatypeFactory.newInstance().newDuration(currentSparqlBinding));
                                    continue;
                                }

                                //RdfResource
                                if(RdfResource.class.isAssignableFrom(currentType))
                                {
                                    entry.getValue().invoke(returnObject, new RdfResource(currentSparqlBinding));
                                    continue;
                                }

                            } else {
                                System.out.println(entry.getValue().getParameterTypes()[0].getName() + " is not primitive");

                                continue; //TODO
                                //TODO: handleObject();
                            }
                        }
                        entry.getValue().invoke(returnObject, querySolution.get(sparqlParameterName));
                    }

                }
            }


            return returnObject;
        }
        catch (NoSuchMethodException | NullPointerException | IllegalAccessException | InstantiationException | InvocationTargetException | NoSuchFieldException | URISyntaxException | DatatypeConfigurationException e)
        {
            throw new IOException("Failed to instantiate desired class", e);
        }
    }

    private final Map<String,Class<?>> builtInMap = new HashMap<>();{
        builtInMap.put("int", Integer.TYPE );
        builtInMap.put("long", Long.TYPE );
        builtInMap.put("double", Double.TYPE );
        builtInMap.put("float", Float.TYPE );
        builtInMap.put("bool", Boolean.TYPE );
        builtInMap.put("char", Character.TYPE );
        builtInMap.put("byte", Byte.TYPE );
        builtInMap.put("void", Void.TYPE );
        builtInMap.put("short", Short.TYPE );
    }

    private boolean isArrayListTypePrimitive(Type t) throws IOException {
        String typeName = t.getTypeName();
        if(!typeName.startsWith("java.util.ArrayList<? extends "))
        {
            throw new IOException("Illegal argument encountered while interpreting type parameter");
        }
        //last space is where we want to cut off (right after the "extends"), as well as removing the last closing braces
        typeName = typeName.substring(typeName.lastIndexOf(" "), typeName.length() - 1);
        System.out.println("Extracted type name from ArrayList: " + typeName);

        try {
            //Do not try to call Class.forName(primitive) -- that would throw an exception
            if(builtInMap.containsKey(typeName)) return true;
            return isPrimitive(Class.forName(typeName));
        }
        catch (ClassNotFoundException e)
        {
            throw new IOException("Unable to retrieve class from generic");
        }
    }

    private boolean isPrimitive(Class<?> input)
    {
        //TODO: collection, (but not Map? May not matter, as we excluded the "properties" method)

        //Collections are not simple
        if(Collection.class.isAssignableFrom(input))
        {
            System.out.println("Encountered collection in isPrimitive. Use isArrayListTypePrimitive instead");
            return false;
        }

        //check for: plain/typed literal, XMLGregorianCalendar, byte[], RdfResource
        //covers int, long, short, float, double, boolean, byte
        if(input.isPrimitive()) return true;

        //TODO: is "." placeholder or a dot?
        //covers URI, String, BigInteger, BigDecimal, Duration
        if(input.getName().startsWith("java.") || input.getName().startsWith("javax.")) return true;

        //TODO: complete this. Or: do some other check, such as test the name space
        if(URI.class.isAssignableFrom(input) || String.class.isAssignableFrom(input))
            return true;

        return false;
    }

    public <T> T parseMessage(String message, Class<T> targetClass) throws IOException {
        init();
        Model model = MessageParser.readMessage(message);

        ArrayList<Class<?>> implementingClasses = MessageParser.getImplementingClasses(targetClass);

        String queryString = "SELECT ?id ?type { ?id a ?type . }";
        Query query = QueryFactory.create(queryString);
        QueryExecution queryExecution = QueryExecutionFactory.create(query, model);
        ResultSet resultSet = queryExecution.execSelect();

        if(!resultSet.hasNext())
        {
            throw new IOException("Could not extract class from input message");
        }

        Class<?> returnClass = null;
        String returnId = null;

        while (resultSet.hasNext()) {
            QuerySolution solution = resultSet.nextSolution();
            String fullName = solution.get("type").toString();
            String className = fullName.substring(fullName.lastIndexOf('/') + 1);

            for(Class<?> currentClass : implementingClasses)
            {
                if(currentClass.getSimpleName().equals(className + "Impl"))
                {
                    returnId = solution.get("id").toString();
                    returnClass = currentClass;
                    System.out.println("Found implementing class: " + currentClass.getSimpleName());
                    break;
                }
            }
            if(returnClass != null) break;
        }

        if(returnClass == null)
        {
            throw new NullPointerException("Could not determine an appropriate implementing class for " + targetClass.getName());
        }

        //At this point, we parsed the model and know to which implementing class we want to parse


        return (T)handleObject(model, returnId, returnClass);

    }


    /**
     * This function initialized the MessageParser class. It will be called automatically upon being requested to read a message.
     * You can manually call this function on startup to avoid a delay later on.
     * Note that you can choose whether to download the ontology or from resources via the downloadOntology static variable.
     * @throws IOException if the ontology cannot be loaded
     */
    public static void init() throws IOException {
        //Check if ontology model exists yet
        if(ontologyModel != null)
        {
            return;
        }

        //Does not exist. Initialize
        ontologyModel = JenaUtil.createMemoryModel();

        //Load ontology
        InputStream inputStream;

        //From web or from local file?
        if(downloadOntology) {
            //try to download from GitHub
            URL url;
            try {

                //TODO
                url = new URL("https://github.com/International-Data-Spaces-Association/InformationModel/releases/download/v4.0.0/IDS-InformationModel-v4.0.0.ttl");
            }
            catch (MalformedURLException e)
            {
                throw new IOException("Failed to download ontology.", e);
            }
            inputStream = url.openConnection().getInputStream();
        }
        else {
            //Load from local file
            inputStream = MessageParser.class.getResourceAsStream("ontology.ttl");
        }
        //Pipe the input stream (whether it is from web or local file) into Apache Jena model
        ontologyModel.read(inputStream, null, FileUtils.langTurtle);
    }

    /**
     * Reads a message into an Apache Jena model.
     * If the class was not previously initialized, it will automatically initialize upon this function call.
     * @param message Message to be read
     * @return The model of the message plus ontology
     */
    public static Model readMessage(String message) {

        Model targetModel = JenaUtil.createMemoryModel();

        //Read incoming message to the same model

        RDFDataMgr.read(targetModel, new ByteArrayInputStream(message.getBytes()), RDFLanguages.JSONLD);

        return targetModel;
    }

    public static Model readMessageAndOntology(String message) throws IOException {

        //Make sure ontology is initialized (does nothing if already initialized)
        init();

        //Copy ontology model
        Model combinedModel = ontologyModel;

        //Read incoming message to the same model
        RDFParser.create()
                .source(new ByteArrayInputStream(message.getBytes()))
                .lang(Lang.JSONLD)
                .errorHandler(ErrorHandlerFactory.getDefaultErrorHandler())
                .parse(combinedModel.getGraph());
        return combinedModel;
    }

    //TODO to be deleted
    public <T> void getDeclaredFields(Class<T> bean)
    {
        Field[] fields = bean.getDeclaredFields();
        if(bean.isInterface())
        {
            System.out.println("Note that interfaces have no fields.");
        }
        else
        {
            System.out.println("Num fields: " + fields.length);
        }
        for(Field field : fields)
        {
            System.out.println(field.getName());
        }

        Method[] methods = bean.getMethods();
        for(Method method : methods) {
            System.out.println(method.getReturnType().getName() + " " + method.getName());
        }

        List<String> methodNames = Arrays.stream(methods).filter(method -> {
            String name = method.getName();
            //Filter out irrelevant methods
            return name.startsWith("get") && !name.equals("getProperties") && !name.equals("getComment") && !name.equals("getLabel");
        }).map(method -> {
            //Remove "get" part
            String reducedName = method.getName().substring(3);

            //Turn first character to lower case
            char[] c = reducedName.toCharArray();
            c[0] = Character.toLowerCase(c[0]);
            return new String(c);
        }).collect(Collectors.toList());

        System.out.println("METHOD NAMES");
        methodNames.forEach(System.out::println);


        System.out.println("IMPLEMENTING CLASSES");
        ArrayList<Class<?>> implementingClasses = getImplementingClasses(bean);
        for(Class<?> impl : implementingClasses)
        {
            System.out.println(impl.getName());
        }

    }

    public static ArrayList<Class<?>> getImplementingClasses(Class<?> someClass)
    {
        ArrayList<Class<?>> result = new ArrayList<>();
        JsonSubTypes subTypeAnnotation = someClass.getAnnotation(JsonSubTypes.class);
        if(subTypeAnnotation != null) {
            JsonSubTypes.Type[] types = subTypeAnnotation.value();
            for(JsonSubTypes.Type type : types)
            {
                result.addAll(getImplementingClasses(type.value()));
            }
        }
        if(!someClass.isInterface())
            result.add(someClass);
        return result;
    }

}
