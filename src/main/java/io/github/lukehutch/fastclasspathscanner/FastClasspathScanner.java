/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison <luke .dot. hutch .at. gmail .dot. com>
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Luke Hutchison
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.lukehutch.fastclasspathscanner;

import io.github.lukehutch.fastclasspathscanner.classgraph.ClassGraphBuilder;
import io.github.lukehutch.fastclasspathscanner.classgraph.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.classgraph.ClassfileBinaryParser;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchContentsProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.InterfaceMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubclassMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubinterfaceMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.scanner.RecursiveScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.RecursiveScanner.FilePathMatcher;
import io.github.lukehutch.fastclasspathscanner.scanner.RecursiveScanner.FilePathTester;
import io.github.lukehutch.fastclasspathscanner.utils.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Uber-fast, ultra-lightweight Java classpath scanner. Scans the classpath by parsing the classfile binary format
 * directly rather than by using reflection. (Reflection causes the classloader to load each class, which can take
 * an order of magnitude more time than parsing the classfile directly.) See the accompanying README.md file for
 * documentation.
 */
public class FastClasspathScanner {
    /** The classpath finder. */
    private final ClasspathFinder classpathFinder = new ClasspathFinder();

    /** The class that recursively scans the classpath. */
    private final RecursiveScanner recursiveScanner;

    /**
     * A map from classname, to static final field name, to a StaticFinalFieldMatchProcessor that should be called
     * if that class name and static final field name is encountered during scan.
     */
    private final HashMap<String, HashMap<String, StaticFinalFieldMatchProcessor>> //
    classNameToStaticFieldnameToMatchProcessor = new HashMap<>();

    /**
     * A map from relative path to the information extracted from the class. If the class name is encountered more
     * than once (i.e. if the same class is defined in multiple classpath elements), the second and subsequent class
     * definitions are ignored, because they are masked by the earlier definition.
     */
    private final HashMap<String, ClassInfo> relativePathToClassInfo = new HashMap<>();

    /** The class, interface and annotation graph builder. */
    private ClassGraphBuilder classGraphBuilder;

    /** An interface used for testing if a class matches specified criteria. */
    public static interface ClassMatcher {
        public abstract void lookForMatches();
    }

    /** A list of class matchers to call once all classes have been read in from classpath. */
    private final ArrayList<ClassMatcher> classMatchers = new ArrayList<>();

    /** If set to true, print info while scanning */
    public static boolean verbose = false;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructs a FastClasspathScanner instance. You can pass a scanning specification to the constructor to
     * describe what should be scanned. This prevents irrelevant classpath entries from being unecessarily scanned,
     * which can be time-consuming. (Note that calling the constructor does not start the scan, you must separately
     * call .scan() to perform the actual scan.)
     * 
     * @param scanSpec
     *            The constructor accepts a list of whitelisted package prefixes / jar names to scan, as well as
     *            blacklisted packages/jars not to scan, where blacklisted entries are prefixed with the '-'
     *            character. For example:
     * 
     *            new FastClasspathScanner("com.x"): limits scanning to the package com.x and its sub-packages in
     *            all jarfiles and all directory entries on the classpath.
     * 
     *            new FastClasspathScanner("com.x", "-com.x.y"): limits scanning to com.x and all sub-packages
     *            *except* com.x.y in all jars and directories on the classpath.
     * 
     *            new FastClasspathScanner("com.x", "-com.x.y", "jar:deploy.jar"): limits scanning to com.x and all
     *            its sub-packages except com.x.y, but only looks in jars named deploy.jar on the classpath. Note:
     *            1. Whitelisting one or more jar entries prevents non-jar entries (directories) on the classpath
     *            from being scanned. 2. Only the leafname of a jarfile can be specified in a "jar:" or "-jar:"
     *            entry, so if there is a chance of conflict, make sure the jarfile's leaf name is unique.
     * 
     *            new FastClasspathScanner("com.x", "-jar:irrelevant.jar"): limits scanning to com.x and all
     *            sub-packages in all directories on the classpath, and in all jars except irrelevant.jar. (i.e.
     *            blacklisting a jarfile only excludes the specified jarfile, it doesn't prevent all directories
     *            from being scanned, as with whitelisting a jarfile.)
     * 
     *            new FastClasspathScanner("com.x", "jar:"): limits scanning to com.x and all sub-packages, but only
     *            looks in jarfiles on the classpath -- directories are not scanned. (i.e. "jar:" is a wildcard to
     *            indicate that all jars are whitelisted, and as in the example above, whitelisting jarfiles
     *            prevents non-jars (directories) from being scanned.)
     * 
     *            new FastClasspathScanner("com.x", "-jar:"): limits scanning to com.x and all sub-packages, but
     *            only looks in directories on the classpath -- jarfiles are not scanned. (i.e. "-jar:" is a
     *            wildcard to indicate that all jars are blacklisted.)
     * 
     *            new FastClasspathScanner(): If you don't specify any whitelisted package prefixes, all jarfiles
     *            and all directories on the classpath will be scanned.
     * 
     *            N.B. System, bootstrap and extension jarfiles (i.e. the JRE jarfiles) are never scanned.
     */
    public FastClasspathScanner(final String... scanSpec) {
        this.recursiveScanner = new RecursiveScanner(classpathFinder, scanSpec);

        // Read classfile headers for all filenames ending in ".class" on classpath
        this.matchFilenameExtension("class", new FileMatchProcessor() {
            @Override
            public void processMatch(final String relativePath, final InputStream inputStream, //
                    final int lengthBytes) throws IOException {
                // Make sure this was the first occurrence of the given relativePath on the classpath,
                // to enable masking of classes
                if (!relativePathToClassInfo.containsKey(relativePath)) {
                    // This is the first time we have encountered this relative path (and therefore this class)
                    // on the classpath. Read class info from classfile binary header.
                    final ClassInfo classInfo = ClassfileBinaryParser.readClassInfoFromClassfileHeader(
                            relativePath, inputStream, classNameToStaticFieldnameToMatchProcessor);
                    if (classInfo != null) {
                        // If class info was successfully read, store a mapping from relative path to class info.
                        // (All classes with the same name should have the same relative path, although this is
                        // checked when the classfile is read.)
                        relativePathToClassInfo.put(relativePath, classInfo);
                    }
                } else {
                    // The new class was masked by a class with the same name earlier in the classpath.
                    if (verbose) {
                        Log.log(relativePath.replace('/', '.')
                                + " occurs more than once on classpath, ignoring all but first instance");
                    }
                }
            }
        });
    }

    /** Override the automatically-detected classpath with a custom search path. */
    public FastClasspathScanner overrideClasspath(final String classpath) {
        classpathFinder.overrideClasspath(classpath);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Call the classloader using Class.forName(className). Re-throws classloading exceptions as RuntimeException.
     */
    private <T> Class<? extends T> loadClass(final String className) {
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends T> cls = (Class<? extends T>) Class.forName(className);
            return cls;
        } catch (ClassNotFoundException | NoClassDefFoundError | ExceptionInInitializerError e) {
            throw new RuntimeException("Exception while loading or initializing class " + className, e);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check a class is an annotation (throws an IllegalArgumentException if not), and return the name of the
     * annotation.
     */
    private static String annotationName(final Class<?> annotation) {
        if (!annotation.isAnnotation()) {
            throw new IllegalArgumentException("Class " + annotation.getName() + " is not an annotation");
        }
        return annotation.getName();
    }

    /**
     * Check each element of an array of classes is an annotation (throws an IllegalArgumentException if not), and
     * return the names of the classes as an array of strings.
     */
    private static String[] annotationNames(final Class<?>[] annotations) {
        final String[] annotationNames = new String[annotations.length];
        for (int i = 0; i < annotations.length; i++) {
            annotationNames[i] = annotationName(annotations[i]);
        }
        return annotationNames;
    }

    /**
     * Check a class is an interface (throws an IllegalArgumentException if not), and return the name of the
     * interface.
     */
    private static String interfaceName(final Class<?> iface) {
        if (!iface.isInterface()) {
            throw new IllegalArgumentException("Class " + iface.getName() + " is not an interface");
        }
        return iface.getName();
    }

    /**
     * Check each element of an array of classes is an interface (throws an IllegalArgumentException if not), and
     * return the names of the classes as an array of strings.
     */
    private static String[] interfaceNames(final Class<?>[] interfaces) {
        final String[] interfaceNames = new String[interfaces.length];
        for (int i = 0; i < interfaces.length; i++) {
            interfaceNames[i] = interfaceName(interfaces[i]);
        }
        return interfaceNames;
    }

    /**
     * Check a class is a regular class or interface (not an annotation -- throws an IllegalArgumentException
     * otherwise), and return the name of the class or interface.
     */
    private static String classOrInterfaceName(final Class<?> classOrInterface) {
        if (classOrInterface.isAnnotation()) {
            throw new IllegalArgumentException(classOrInterface.getName()
                    + " is an annotation, not a regular class or interface");
        }
        return classOrInterface.getName();
    }

    /**
     * Check a class is a regular class (not an interface or annotation -- throws an IllegalArgumentException if not
     * a regular class), and return the name of the class.
     */
    private static String className(final Class<?> cls) {
        if (cls.isAnnotation()) {
            throw new IllegalArgumentException(cls.getName() + " is an annotation, not a regular class");
        } else if (cls.isInterface()) {
            throw new IllegalArgumentException(cls.getName() + " is an interface, not a regular class");
        }
        return cls.getName();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided SubclassMatchProcessor if classes are found on the classpath that extend the specified
     * superclass. Will call the class loader on each matching class (using Class.forName()) before calling the
     * SubclassMatchProcessor. Does not call the classloader on non-matching classes or interfaces.
     * 
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @param subclassMatchProcessor
     *            the SubclassMatchProcessor to call when a match is found.
     */
    public <T> FastClasspathScanner matchSubclassesOf(final Class<T> superclass,
            final SubclassMatchProcessor<T> subclassMatchProcessor) {
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                final String superclassName = className(superclass);
                for (final String subclassName : getNamesOfSubclassesOf(superclassName)) {
                    if (verbose) {
                        Log.log("Found subclass of " + superclassName + ": " + subclassName);
                    }
                    // Call classloader
                    final Class<? extends T> cls = loadClass(subclassName);
                    // Process match
                    subclassMatchProcessor.processMatch(cls);
                }
            }
        });
        return this;
    }

    /**
     * Returns the names of classes on the classpath that extend the specified superclass. Should be called after
     * scan(), and returns matching classes whether or not a SubclassMatchProcessor was added to the scanner before
     * the call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfSubclassesOf(final Class<?> superclass) {
        return getNamesOfSubclassesOf(className(superclass));
    }

    /**
     * Returns the names of classes on the classpath that extend the specified superclass. Should be called after
     * scan(), and returns matching classes whether or not a SubclassMatchProcessor was added to the scanner before
     * the call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param superclassName
     *            The name of the superclass to match (i.e. the name of the class that subclasses need to extend).
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfSubclassesOf(final String superclassName) {
        return getScanResults().getNamesOfSubclassesOf(superclassName);
    }

    /**
     * Returns the names of classes on the classpath that are superclasses of the specified subclass. Should be
     * called after scan(), and returns matching classes whether or not a SubclassMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param subclass
     *            The subclass to match (i.e. the class that needs to extend a superclass for the superclass to
     *            match).
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfSuperclassesOf(final Class<?> subclass) {
        return getNamesOfSuperclassesOf(className(subclass));
    }

    /**
     * Returns the names of classes on the classpath that are superclasses of the specified subclass. Should be
     * called after scan(), and returns matching classes whether or not a SubclassMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param subclassName
     *            The subclass to match (i.e. the class that needs to extend a superclass for the superclass to
     *            match).
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfSuperclassesOf(final String subclassName) {
        return getScanResults().getNamesOfSuperclassesOf(subclassName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided SubinterfaceMatchProcessor if an interface that extends a given superinterface is found on
     * the classpath. Will call the class loader on each matching interface (using Class.forName()) before calling
     * the SubinterfaceMatchProcessor. Does not call the classloader on non-matching classes or interfaces.
     * 
     * @param superinterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @param subinterfaceMatchProcessor
     *            the SubinterfaceMatchProcessor to call when a match is found.
     */
    public <T> FastClasspathScanner matchSubinterfacesOf(final Class<T> superinterface,
            final SubinterfaceMatchProcessor<T> subinterfaceMatchProcessor) {
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                final String superinterfaceName = interfaceName(superinterface);
                for (final String subinterfaceName : getNamesOfSubinterfacesOf(superinterfaceName)) {
                    if (verbose) {
                        Log.log("Found subinterface of " + superinterfaceName + ": " + subinterfaceName);
                    }
                    // Call classloader
                    final Class<? extends T> cls = loadClass(subinterfaceName);
                    // Process match
                    subinterfaceMatchProcessor.processMatch(cls);
                }
            }
        });
        return this;
    }

    /**
     * Returns the names of interfaces on the classpath that extend a given superinterface. Should be called after
     * scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to the scanner
     * before the call to scan(). Does not call the classloader on the matching interfaces, just returns their
     * names.
     * 
     * @param superInterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public List<String> getNamesOfSubinterfacesOf(final Class<?> superInterface) {
        return getNamesOfSubinterfacesOf(interfaceName(superInterface));
    }

    /**
     * Returns the names of interfaces on the classpath that extend a given superinterface. Should be called after
     * scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to the scanner
     * before the call to scan(). Does not call the classloader on the matching interfaces, just returns their
     * names.
     * 
     * @param superInterfaceName
     *            The name of the superinterface to match (i.e. the name of the interface that subinterfaces need to
     *            extend).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public List<String> getNamesOfSubinterfacesOf(final String superInterfaceName) {
        return getScanResults().getNamesOfSubinterfacesOf(superInterfaceName);
    }

    /**
     * Returns the names of interfaces on the classpath that are superinterfaces of a given subinterface. Should be
     * called after scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to
     * the scanner before the call to scan(). Does not call the classloader on the matching interfaces, just returns
     * their names.
     * 
     * @param subInterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public List<String> getNamesOfSuperinterfacesOf(final Class<?> subInterface) {
        return getNamesOfSuperinterfacesOf(interfaceName(subInterface));
    }

    /**
     * Returns the names of interfaces on the classpath that are superinterfaces of a given subinterface. Should be
     * called after scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to
     * the scanner before the call to scan(). Does not call the classloader on the matching interfaces, just returns
     * their
     * 
     * @param subinterfaceName
     *            The name of the superinterface to match (i.e. the name of the interface that subinterfaces need to
     *            extend).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public List<String> getNamesOfSuperinterfacesOf(final String subinterfaceName) {
        return getScanResults().getNamesOfSuperinterfacesOf(subinterfaceName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided InterfaceMatchProcessor for classes on the classpath that implement the specified
     * interface or a subinterface, or whose superclasses implement the specified interface or a sub-interface. Will
     * call the class loader on each matching interface (using Class.forName()) before calling the
     * InterfaceMatchProcessor. Does not call the classloader on non-matching classes or interfaces.
     * 
     * @param implementedInterface
     *            The interface that classes need to implement.
     * @param interfaceMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public <T> FastClasspathScanner matchClassesImplementing(final Class<T> implementedInterface,
            final InterfaceMatchProcessor<T> interfaceMatchProcessor) {
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                final String implementedInterfaceName = interfaceName(implementedInterface);
                for (final String implClass : getNamesOfClassesImplementing(implementedInterfaceName)) {
                    if (verbose) {
                        Log.log("Found class implementing interface " + implementedInterfaceName + ": " + implClass);
                    }
                    // Call classloader
                    final Class<? extends T> cls = loadClass(implClass);
                    // Process match
                    interfaceMatchProcessor.processMatch(cls);
                }
            }
        });
        return this;
    }

    /**
     * Returns the names of classes on the classpath that implement the specified interface or a subinterface, or
     * whose superclasses implement the specified interface or a sub-interface. Should be called after scan(), and
     * returns matching interfaces whether or not an InterfaceMatchProcessor was added to the scanner before the
     * call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param implementedInterface
     *            The interface that classes need to implement to match.
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfClassesImplementing(final Class<?> implementedInterface) {
        return getNamesOfClassesImplementing(interfaceName(implementedInterface));
    }

    /**
     * Returns the names of classes on the classpath that implement the specified interface or a subinterface, or
     * whose superclasses implement the specified interface or a sub-interface. Should be called after scan(), and
     * returns matching interfaces whether or not an InterfaceMatchProcessor was added to the scanner before the
     * call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param implementedInterfaceName
     *            The name of the interface that classes need to implement.
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfClassesImplementing(final String implementedInterfaceName) {
        return getScanResults().getNamesOfClassesImplementing(implementedInterfaceName);
    }

    /**
     * Returns the names of classes on the classpath that implement (or have superclasses that implement) all of the
     * specified interfaces or their subinterfaces. Should be called after scan(), and returns matching interfaces
     * whether or not an InterfaceMatchProcessor was added to the scanner before the call to scan(). Does not call
     * the classloader on the matching classes, just returns their names.
     * 
     * @param implementedInterfaceNames
     *            The name of the interfaces that classes need to implement.
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfClassesImplementingAllOf(final Class<?>... implementedInterfaces) {
        return getNamesOfClassesImplementingAllOf(interfaceNames(implementedInterfaces));
    }

    /**
     * Returns the names of classes on the classpath that implement (or have superclasses that implement) all of the
     * specified interfaces or their subinterfaces. Should be called after scan(), and returns matching interfaces
     * whether or not an InterfaceMatchProcessor was added to the scanner before the call to scan(). Does not call
     * the classloader on the matching classes, just returns their names.
     * 
     * @param implementedInterfaceNames
     *            The name of the interfaces that classes need to implement.
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfClassesImplementingAllOf(final String... implementedInterfaceNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (int i = 0; i < implementedInterfaceNames.length; i++) {
            final String implementedInterfaceName = implementedInterfaceNames[i];
            final List<String> namesOfImplementingClasses = getNamesOfClassesImplementing(implementedInterfaceName);
            if (i == 0) {
                classNames.addAll(namesOfImplementingClasses);
            } else {
                classNames.retainAll(namesOfImplementingClasses);
            }
        }
        return new ArrayList<>(classNames);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided ClassMatchProcessor if classes are found on the classpath that have the specified
     * annotation.
     * 
     * @param annotation
     *            The class annotation to match.
     * @param classAnnotationMatchProcessor
     *            the ClassAnnotationMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchClassesWithAnnotation(final Class<?> annotation,
            final ClassAnnotationMatchProcessor classAnnotationMatchProcessor) {
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                final String annotationName = annotationName(annotation);
                for (final String classWithAnnotation : getNamesOfClassesWithAnnotation(annotationName)) {
                    if (verbose) {
                        Log.log("Found class with annotation " + annotationName + ": " + classWithAnnotation);
                    }
                    // Call classloader
                    final Class<?> cls = loadClass(classWithAnnotation);
                    // Process match
                    classAnnotationMatchProcessor.processMatch(cls);
                }
            }
        });
        return this;
    }

    /**
     * Returns the names of classes on the classpath that have the specified annotation. Should be called after
     * scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the scanner
     * before the call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param annotation
     *            The class annotation.
     * @return A list of the names of classes with the class annotation, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotation(final Class<?> annotation) {
        return getNamesOfClassesWithAnnotation(annotationName(annotation));
    }

    /**
     * Returns the names of classes on the classpath that have the specified annotation. Should be called after
     * scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the scanner
     * before the call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param annotationName
     *            The name of the class annotation.
     * @return A list of the names of classes that have the named annotation, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        return getScanResults().getNamesOfClassesWithAnnotation(annotationName);
    }

    /**
     * Returns the names of classes on the classpath that have all of the specified annotations. Should be called
     * after scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param annotations
     *            The annotations.
     * @return A list of the names of classes that have all of the annotations, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAllOf(final Class<?>... annotations) {
        return getNamesOfClassesWithAnnotationsAllOf(annotationNames(annotations));
    }

    /**
     * Returns the names of classes on the classpath that have all of the specified annotations. Should be called
     * after scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param annotationNames
     *            The annotation names.
     * @return A list of the names of classes that have all of the annotations, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAllOf(final String... annotationNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (int i = 0; i < annotationNames.length; i++) {
            final String annotationName = annotationNames[i];
            final List<String> namesOfClassesWithMetaAnnotation = getNamesOfClassesWithAnnotation(annotationName);
            if (i == 0) {
                classNames.addAll(namesOfClassesWithMetaAnnotation);
            } else {
                classNames.retainAll(namesOfClassesWithMetaAnnotation);
            }
        }
        return new ArrayList<>(classNames);
    }

    /**
     * Returns the names of classes on the classpath that have any of the specified annotations. Should be called
     * after scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param annotations
     *            The annotations.
     * @return A list of the names of classes that have one or more of the annotations, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAnyOf(final Class<?>... annotations) {
        return getNamesOfClassesWithAnnotationsAnyOf(annotationNames(annotations));
    }

    /**
     * Returns the names of classes on the classpath that have any of the specified annotations. Should be called
     * after scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param annotationNames
     *            The annotation names.
     * @return A list of the names of classes that have one or more of the annotations, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAnyOf(final String... annotationNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (final String annotationName : annotationNames) {
            classNames.addAll(getNamesOfClassesWithAnnotation(annotationName));
        }
        return new ArrayList<>(classNames);
    }

    /**
     * Return the names of all annotations that are annotated with the specified meta-annotation.
     * 
     * @param metaAnnotation
     *            The specified meta-annotation.
     * @return A list of the names of annotations that are annotated with the specified meta annotation, or the
     *         empty list if none.
     */
    public List<String> getNamesOfAnnotationsWithMetaAnnotation(final Class<?> metaAnnotation) {
        return getNamesOfAnnotationsWithMetaAnnotation(annotationName(metaAnnotation));
    }

    /**
     * Return the names of all annotations that are annotated with the specified meta-annotation.
     * 
     * @param metaAnnotationName
     *            The name of the specified meta-annotation.
     * @return A list of the names of annotations that are annotated with the specified meta annotation, or the
     *         empty list if none.
     */
    public List<String> getNamesOfAnnotationsWithMetaAnnotation(final String metaAnnotationName) {
        return getScanResults().getNamesOfAnnotationsWithMetaAnnotation(metaAnnotationName);
    }

    /**
     * Return the names of all annotations and meta-annotations on the specified class or interface.
     * 
     * @param classOrInterface
     *            The class or interface.
     * @return A list of the names of annotations and meta-annotations on the class, or the empty list if none.
     */
    public List<String> getNamesOfAnnotationsOnClass(final Class<?> classOrInterface) {
        return getNamesOfAnnotationsOnClass(classOrInterfaceName(classOrInterface));
    }

    /**
     * Return the names of all annotations and meta-annotations on the specified class or interface.
     * 
     * @param classOrInterfaceName
     *            The name of the class or interface.
     * @return A list of the names of annotations and meta-annotations on the class, or the empty list if none.
     */
    public List<String> getNamesOfAnnotationsOnClass(final String classOrInterfaceName) {
        return getScanResults().getNamesOfAnnotationsOnClass(classOrInterfaceName);
    }

    /**
     * Return the names of all meta-annotations on the specified annotation.
     * 
     * @param annotation
     *            The specified annotation.
     * @return A list of the names of meta-annotations on the specified annotation, or the empty list if none.
     */
    public List<String> getNamesOfMetaAnnotationsOnAnnotation(final Class<?> annotation) {
        return getNamesOfMetaAnnotationsOnAnnotation(annotationName(annotation));
    }

    /**
     * Return the names of all meta-annotations on the specified annotation.
     * 
     * @param annotationName
     *            The name of the specified annotation.
     * @return A list of the names of meta-annotations on the specified annotation, or the empty list if none.
     */
    public List<String> getNamesOfMetaAnnotationsOnAnnotation(final String annotationName) {
        return getScanResults().getNamesOfMetaAnnotationsOnAnnotation(annotationName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a StaticFinalFieldMatchProcessor that should be called if a static final field with the given name is
     * encountered in a class with the given fully-qualified classname while reading a classfile header.
     */
    private void addStaticFinalFieldProcessor(final String className, final String fieldName,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        HashMap<String, StaticFinalFieldMatchProcessor> fieldNameToMatchProcessor = //
        classNameToStaticFieldnameToMatchProcessor.get(className);
        if (fieldNameToMatchProcessor == null) {
            classNameToStaticFieldnameToMatchProcessor.put(className, fieldNameToMatchProcessor = new HashMap<>(2));
        }
        fieldNameToMatchProcessor.put(fieldName, staticFinalFieldMatchProcessor);
    }

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match one of a set of fully-qualified field names, e.g.
     * "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked; the value of the field in the classfile is returned
     * whether or not it should be visible to the caller.
     * 
     * @param fullyQualifiedStaticFinalFieldNames
     *            The set of fully-qualified static field names to match.
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchStaticFinalFieldNames(
            final HashSet<String> fullyQualifiedStaticFinalFieldNames,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        for (final String fullyQualifiedFieldName : fullyQualifiedStaticFinalFieldNames) {
            final int lastDotIdx = fullyQualifiedFieldName.lastIndexOf('.');
            if (lastDotIdx > 0) {
                final String className = fullyQualifiedFieldName.substring(0, lastDotIdx);
                final String fieldName = fullyQualifiedFieldName.substring(lastDotIdx + 1);
                addStaticFinalFieldProcessor(className, fieldName, staticFinalFieldMatchProcessor);
            }
        }
        return this;
    }

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match a fully-qualified field name, e.g. "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked; the value of the field in the classfile is returned
     * whether or not it should be visible to the caller.
     * 
     * @param fullyQualifiedStaticFinalFieldName
     *            The fully-qualified static field name to match
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchStaticFinalFieldNames(final String fullyQualifiedStaticFinalFieldName,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        final HashSet<String> fullyQualifiedStaticFinalFieldNamesSet = new HashSet<>();
        fullyQualifiedStaticFinalFieldNamesSet.add(fullyQualifiedStaticFinalFieldName);
        return matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldNamesSet, staticFinalFieldMatchProcessor);
    }

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match one of a list of fully-qualified field names, e.g.
     * "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked; the value of the field in the classfile is returned
     * whether or not it should be visible to the caller.
     * 
     * @param fullyQualifiedStaticFinalFieldNames
     *            The list of fully-qualified static field names to match.
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchStaticFinalFieldNames(final String[] fullyQualifiedStaticFinalFieldNames,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        final HashSet<String> fullyQualifiedStaticFinalFieldNamesSet = new HashSet<>();
        for (final String fullyQualifiedFieldName : fullyQualifiedStaticFinalFieldNames) {
            fullyQualifiedStaticFinalFieldNamesSet.add(fullyQualifiedFieldName);
        }
        return matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldNamesSet, staticFinalFieldMatchProcessor);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Wrap a FileMatchContentsProcessor in a FileMatchProcessor that reads the file content from the stream. */
    private static FileMatchProcessor wrapFileMatchContentsProcessor(
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        return new FileMatchProcessor() {
            @Override
            public void processMatch(final String relativePath, final InputStream inputStream, //
                    final int lengthBytes) throws IOException {
                // Read the file contents into a byte[] array
                final byte[] contents = new byte[lengthBytes];
                final int bytesRead = Math.max(0, inputStream.read(contents));
                // For safety, truncate the array if the file was truncated before we finish reading it
                final byte[] contentsRead = bytesRead == lengthBytes ? contents : Arrays
                        .copyOf(contents, bytesRead);
                // Pass file contents to the wrapped FileMatchContentsProcessor
                fileMatchContentsProcessor.processMatch(relativePath, contentsRead);
            }
        };
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath with the given regexp pattern in their
     * path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchProcessor fileMatchProcessor) {
        recursiveScanner.addFilePathMatcher(new FilePathMatcher(new FilePathTester() {
            private final Pattern pattern = Pattern.compile(pathRegexp);

            @Override
            public boolean filePathMatches(final String relativePath) {
                return pattern.matcher(relativePath).matches();
            }
        }, fileMatchProcessor));
        return this;
    }

    /**
     * Calls the given FileMatchContentsProcessor if files are found on the classpath with the given regexp pattern
     * in their path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        return matchFilenamePattern(pathRegexp, wrapFileMatchContentsProcessor(fileMatchContentsProcessor));
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that exactly match the given relative
     * path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        recursiveScanner.addFilePathMatcher(new FilePathMatcher(new FilePathTester() {
            @Override
            public boolean filePathMatches(final String relativePath) {
                return relativePath.equals(relativePathToMatch);
            }
        }, fileMatchProcessor));
        return this;
    }

    /**
     * Calls the given FileMatchContentsProcessor if files are found on the classpath that exactly match the given
     * relative path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        return matchFilenamePath(relativePathToMatch, wrapFileMatchContentsProcessor(fileMatchContentsProcessor));
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that exactly match the given path
     * leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        recursiveScanner.addFilePathMatcher(new FilePathMatcher(new FilePathTester() {
            private final String leafToMatch = pathLeafToMatch.substring(pathLeafToMatch.lastIndexOf('/') + 1);

            @Override
            public boolean filePathMatches(final String relativePath) {
                final String relativePathLeaf = relativePath.substring(relativePath.lastIndexOf('/') + 1);
                return relativePathLeaf.equals(leafToMatch);
            }
        }, fileMatchProcessor));
        return this;
    }

    /**
     * Calls the given FileMatchContentsProcessor if files are found on the classpath that exactly match the given
     * path leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        return matchFilenamePathLeaf(pathLeafToMatch, wrapFileMatchContentsProcessor(fileMatchContentsProcessor));
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that have the given file extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html" and "WIDGET.HTML".
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        recursiveScanner.addFilePathMatcher(new FilePathMatcher(new FilePathTester() {
            private final String suffixToMatch = "." + extensionToMatch.toLowerCase();

            @Override
            public boolean filePathMatches(final String relativePath) {
                return relativePath.toLowerCase().endsWith(suffixToMatch);
            }
        }, fileMatchProcessor));
        return this;
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that have the given file extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html".
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        return matchFilenameExtension(extensionToMatch, wrapFileMatchContentsProcessor(fileMatchContentsProcessor));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the names of all classes and interfaces processed during the scan, i.e. all classes reachable after
     * taking into account the package whitelist and blacklist criteria. List is sorted.
     */
    public List<String> getNamesOfAllClasses() {
        return getScanResults().getNamesOfAllClasses();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Classpath elements that do not exist are not included in the list.
     */
    public List<File> getUniqueClasspathElements() {
        return classpathFinder.getUniqueClasspathElements();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the ClassGraphBuilder created by calling .scan(), or throws RuntimeException if .scan() has not yet
     * been called.
     */
    public ClassGraphBuilder getScanResults() {
        if (classGraphBuilder == null) {
            throw new RuntimeException("Must call .scan() before attempting to get the results of the scan");
        }
        return classGraphBuilder;
    }

    /**
     * Scans the classpath for matching files, and calls any match processors if a match is identified.
     * 
     * This method should be called after all required match processors have been added.
     * 
     * This method should be called before any "getNamesOf" methods (e.g. getNamesOfSubclassesOf()).
     */
    public FastClasspathScanner scan() {
        final long scanStart = System.currentTimeMillis();

        relativePathToClassInfo.clear();

        // Scan classpath, calling FilePathMatchers if any matching paths are found, including the matcher
        // that calls the classfile binary parser when the extension ".class" is found on a filename,
        // producing a ClassInfo object for each encountered class.
        recursiveScanner.scan();

        // Build class, interface and annotation graph out of all the ClassInfo objects.
        classGraphBuilder = new ClassGraphBuilder(relativePathToClassInfo.values());

        // Call any class, interface and annotation MatchProcessors
        for (final ClassMatcher classMatcher : classMatchers) {
            classMatcher.lookForMatches();
        }

        if (FastClasspathScanner.verbose) {
            Log.log("*** Time taken by .scan(): " //
                    + (System.currentTimeMillis() - scanStart) + " ms ***");
        }
        return this;
    }

    /**
     * Returns true if the classpath contents have been changed since scan() was last called. Only considers
     * classpath prefixes whitelisted in the call to the constructor. Returns true if scan() has not yet been run.
     * Much faster than standard classpath scanning, because only timestamps are checked, and jarfiles don't have to
     * be opened.
     */
    public boolean classpathContentsModifiedSinceScan() {
        final long scanStart = System.currentTimeMillis();

        final boolean modified = recursiveScanner.classpathContentsModifiedSinceScan();

        if (FastClasspathScanner.verbose) {
            Log.log("*** Time taken by .classpathContentsModifiedSinceScan(): "
                    + (System.currentTimeMillis() - scanStart) + " ms ***");
        }
        return modified;
    }

    /**
     * Returns the maximum "last modified" timestamp in the classpath (in epoch millis), or zero if scan() has not
     * yet been called (or if nothing was found on the classpath).
     * 
     * The returned timestamp should be less than the current system time if the timestamps of files on the
     * classpath and the system time are accurate. Therefore, if anything changes on the classpath, this value
     * should increase.
     */
    public long classpathContentsLastModifiedTime() {
        return recursiveScanner.classpathContentsLastModifiedTime();
    }

    /** Switch on verbose mode (prints debug info to System.out). */
    public FastClasspathScanner verbose() {
        verbose = true;
        return this;
    }
}
