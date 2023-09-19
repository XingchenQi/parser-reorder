package edu.utexas.ece.sa.tools.utility;

import com.github.javaparser.JavaParser;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.visitor.GenericVisitor;
import com.github.javaparser.ast.visitor.VoidVisitor;
import edu.illinois.cs.testrunner.runner.Runner;
import edu.utexas.ece.sa.tools.parser.JavaFile;
import edu.utexas.ece.sa.tools.parser.ParserPathManager;
import org.apache.commons.io.FilenameUtils;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Stream;

public class Refactor {
    private static MavenProject mavenProject;

    private static String classPath;

    private static URLClassLoader projectClassLoader;

    private static Runner runner;

    public Refactor(MavenProject mavenProject, String classPath, URLClassLoader projectClassLoader, Runner runner) {
        Refactor.mavenProject = mavenProject;
        Refactor.classPath = classPath;
        Refactor.projectClassLoader = projectClassLoader;
        Refactor.runner = runner;
    }

    public void updateJUnitTestFiles(JavaFile javaFile)
            throws DependencyResolutionRequiredException,
            ClassNotFoundException, IOException {
        // JUnit 4
        if (runner.framework().getDelimiter().equals(".")) {
            Set<String> methsSet = new HashSet<>();
            Set<String> fldsSet = new HashSet<>();
            updateJUnit4TestFiles(javaFile, true, methsSet, fldsSet);
        }
        // JUnit 5
        if (runner.framework().getDelimiter().equals("#")) {
            Set<String> methsSet = new HashSet<>();
            Set<String> fldsSet = new HashSet<>();
            updateJUnit5TestFiles(javaFile, true, methsSet, fldsSet);
        }
        javaFile.writeAndReloadCompilationUnit();
    }

    protected static void updateJUnit4TestFiles(JavaFile javaFile, boolean lowLevel, Set<String> methodsSet,
                                                Set<String> fieldsSet)
            throws DependencyResolutionRequiredException, ClassNotFoundException, IOException {
        // Before method
        addClassAnnotations(javaFile, fieldsSet, methodsSet, "Before",
                "org.junit.BeforeClass");
        // After Method
        addClassAnnotations(javaFile, fieldsSet, methodsSet, "After",
                "org.junit.AfterClass");

        NodeList<BodyDeclaration> bds = new NodeList<>();
        NodeList<TypeDeclaration<?>> typeDeclarations = javaFile.compilationUnit().getTypes();
        for (TypeDeclaration typeDec : typeDeclarations) {
            List<BodyDeclaration> members = new ArrayList<>(typeDec.getMembers());
            List<BodyDeclaration> membersPendingToRemove = typeDec.getMembers();
            if (members != null) {
                for (BodyDeclaration member : members) {
                    if (member instanceof ConstructorDeclaration) {
                        BodyDeclaration bd = new InitializerDeclaration();
                        BlockStmt blockStmt = new BlockStmt();
                        ConstructorDeclaration m = (ConstructorDeclaration) member;
                        blockStmt.setStatements(m.getBody().getStatements());
                        bd.asInitializerDeclaration().setBody(blockStmt);
                        bds.add(bd);
                        membersPendingToRemove.remove(m);
                    }
                }
                members.removeAll(bds);
            }
        }

        if (!lowLevel) return;
        List<JavaFile> javaFileList = new LinkedList<>();
        JavaFile curFile = javaFile;
        javaFileList.add(curFile);
        while (curFile.hasExtendedJavaFile()) {
            JavaFile parentFile = curFile.getExtendedJavaFile();

            // previous file information
            JavaFile backupFile = curFile;

            // obtain the extend relationship
            getUpperLevelClasses(parentFile);

            String backupName = backupFile.path().getFileName().toString();
            String backupShortName = backupName.substring(0, backupName.lastIndexOf("."));

            int i = 0;
            if (backupFile.hasExtendedJavaFile()) {
                String futureName = backupFile.getExtendedJavaFile().path().getFileName().toString();
                String futureShortName = futureName.substring(0, futureName.lastIndexOf("."));

                for (ClassOrInterfaceType ct : backupFile.getCurCI().getExtendedTypes()) {
                    if (ct.getName().asString().equals(futureShortName)) {
                        String potentialName = futureShortName + "New" + curFile.getExtendedJavaFile().getCurIndex();
                        backupFile.getCurCI().getExtendedTypes(i).setName(potentialName);
                        List<ClassOrInterfaceDeclaration> classList = backupFile.compilationUnit().findAll(
                                ClassOrInterfaceDeclaration.class);
                        for (ClassOrInterfaceDeclaration clazz : classList) {
                            if (clazz.getNameAsString().equals(backupShortName)) {
                                clazz.getExtendedTypes(i).setName(potentialName);
                            }
                        }
                        String clazzName = backupFile.getExtendedJavaFile().compilationUnit().getPackageDeclaration().
                                get().getName().toString() + "." + futureShortName + "New" +
                                curFile.getExtendedJavaFile().getCurIndex();
                        backupFile.compilationUnit().addImport(clazzName);
                        backupFile.writeAndReloadCompilationUnit();
                    }
                    i++;
                }
            }
            int curIndex = parentFile.getCurIndex();
            Path path1 = ParserPathManager.backupPath1(parentFile.path(), "New" + curIndex + ".java");
            String fileName = parentFile.path().getFileName().toString();
            String fileShortName = fileName.substring(0, fileName.lastIndexOf("."));
            Utils.backup1(parentFile, "New" + curIndex + ".java");

            curFile = JavaFile.loadFile(path1, classPath, ParserPathManager.compiledPath(path1).getParent(),
                    fileShortName, "New" + curIndex);
            parentFile.setCurIndex(curIndex + 1);
            backupFile.setExtendedJavaFile(curFile);
            curFile.setExtendedJavaFile(parentFile.getExtendedJavaFile());
            curFile.writeAndReloadCompilationUnit();
            updateJUnit4TestFiles(curFile, false, methodsSet, fieldsSet);
            javaFileList.add(curFile);
        }
        updateMethods(javaFile, methodsSet, fieldsSet);
    }

    protected static void updateJUnit5TestFiles(JavaFile javaFile, boolean lowLevel,
                                         Set<String> methodsSet, Set<String> fieldsSet)
            throws DependencyResolutionRequiredException, ClassNotFoundException, IOException {
        // BeforeEach method
        addClassAnnotations(javaFile, fieldsSet, methodsSet, "BeforeEach",
                "org.junit.jupiter.api.BeforeAll");
        // AfterEach Method
        addClassAnnotations(javaFile, fieldsSet, methodsSet, "AfterEach",
                "org.junit.jupiter.api.AfterAll");

        NodeList<BodyDeclaration> bds = new NodeList<>();
        NodeList<TypeDeclaration<?>> typeDeclarations = javaFile.compilationUnit().getTypes();
        for (TypeDeclaration typeDec : typeDeclarations) {
            List<BodyDeclaration> members = new ArrayList<>(typeDec.getMembers());
            List<BodyDeclaration> membersPendingToRemove = typeDec.getMembers();
            if (members != null) {
                for (BodyDeclaration member : members) {
                    if (member instanceof ConstructorDeclaration) {
                        BodyDeclaration bd = new InitializerDeclaration();
                        BlockStmt blockStmt = new BlockStmt();
                        ConstructorDeclaration m = (ConstructorDeclaration) member;
                        blockStmt.setStatements(m.getBody().getStatements());
                        bd.asInitializerDeclaration().setBody(blockStmt);
                        bds.add(bd);
                        membersPendingToRemove.remove(m);
                    }
                }
                members.removeAll(bds);
            }
        }

        if (!lowLevel) return;
        List<JavaFile> javaFileList = new LinkedList<>();
        JavaFile curFile = javaFile;
        javaFileList.add(curFile);
        while (curFile.hasExtendedJavaFile()) {
            JavaFile parentFile = curFile.getExtendedJavaFile();

            // previous file information
            JavaFile backupFile = curFile;

            // obtain the extend relationship
            getUpperLevelClasses(parentFile);

            String backupName = backupFile.path().getFileName().toString();
            String backupShortName = backupName.substring(0, backupName.lastIndexOf("."));

            int i = 0;
            if (backupFile.hasExtendedJavaFile()) {
                String futureName = backupFile.getExtendedJavaFile().path().getFileName().toString();
                String futureShortName = futureName.substring(0, futureName.lastIndexOf("."));

                for (ClassOrInterfaceType ct : backupFile.getCurCI().getExtendedTypes()) {
                    if (ct.getName().asString().equals(futureShortName)) {
                        String potentialName = futureShortName + "New" + curFile.getExtendedJavaFile().getCurIndex();
                        backupFile.getCurCI().getExtendedTypes(i).setName(potentialName);
                        List<ClassOrInterfaceDeclaration> classList = backupFile.compilationUnit().findAll(
                                ClassOrInterfaceDeclaration.class);
                        for (ClassOrInterfaceDeclaration clazz : classList) {
                            if (clazz.getNameAsString().equals(backupShortName)) {
                                clazz.getExtendedTypes(i).setName(potentialName);
                            }
                        }
                        String clazzName = backupFile.getExtendedJavaFile().compilationUnit().getPackageDeclaration().
                                get().getName().toString() + "." + futureShortName + "New" +
                                curFile.getExtendedJavaFile().getCurIndex();
                        backupFile.compilationUnit().addImport(clazzName);
                        backupFile.writeAndReloadCompilationUnit();
                    }
                    i++;
                }
            }

            int curIndex = parentFile.getCurIndex();
            Path path1 = ParserPathManager.backupPath1(parentFile.path(), "New" + curIndex + ".java");
            String fileName = parentFile.path().getFileName().toString();
            String fileShortName = fileName.substring(0, fileName.lastIndexOf("."));
            Utils.backup1(parentFile, "New" + curIndex + ".java");

            curFile = JavaFile.loadFile(path1, classPath, ParserPathManager.compiledPath(path1).getParent(),
                    fileShortName, "New" + curIndex);
            parentFile.setCurIndex(curIndex + 1);
            backupFile.setExtendedJavaFile(curFile);
            curFile.setExtendedJavaFile(parentFile.getExtendedJavaFile());
            curFile.writeAndReloadCompilationUnit();
            updateJUnit5TestFiles(curFile, false, methodsSet, fieldsSet);

            javaFileList.add(curFile);
        }
        updateMethods(javaFile, methodsSet, fieldsSet);
    }

    protected static void updateMethods(JavaFile javaFile, Set<String> methodsSet,
                                        Set<String> fieldsSet)
            throws IOException, DependencyResolutionRequiredException, ClassNotFoundException {
        Set<String> remainingMethodsSet = new CopyOnWriteArraySet<>();
        remainingMethodsSet.addAll(methodsSet);

        List<JavaFile> javaFileList = new LinkedList<>();
        JavaFile curFile = javaFile;
        javaFileList.add(curFile);
        while (curFile.hasExtendedJavaFile()) {
            getUpperLevelClasses(curFile);
            JavaFile parentFile = curFile.getExtendedJavaFile();
            curFile = parentFile;
            javaFileList.add(curFile);
        }
        Set<String> processedMethods = new HashSet<>();
        while (true) {
            Set<String> preSet = new HashSet<>(remainingMethodsSet);
            for (String methodName : remainingMethodsSet) {
                if (processedMethods.contains(methodName)) {
                    continue;
                }
                for (JavaFile javaFile1 : javaFileList) {
                    String packageName = javaFile1.compilationUnit().getPackageDeclaration().get().getNameAsString();
                    String className = javaFile1.getCurCI().getNameAsString();
                    List<MethodDeclaration> methodsList = javaFile1.findMethodDeclarations(packageName + "." +
                            className + "." + methodName);
                    if (methodsList.size() > 0) {
                        Set<String> additionalMethods = new HashSet<>();
                        for (MethodDeclaration md : methodsList) {
                            md.setStatic(true);
                            // deal with annotations
                            NodeList<AnnotationExpr> annotations = md.getAnnotations();
                            NodeList<AnnotationExpr> newAnnotations = new NodeList<>();
                            for (AnnotationExpr ae : annotations) {
                                if (ae.getName().toString().equals("Override")) {
                                    continue;
                                } else {
                                    newAnnotations.add(ae);
                                }
                            }
                            md.setAnnotations(newAnnotations);
                            additionalMethods.addAll(getRelatedMethods(md));
                            fieldsSet.addAll(getRelatedFields(md, javaFile1, false));
                            changeMethods(md, javaFile1);
                        }
                        remainingMethodsSet.addAll(additionalMethods);
                        remainingMethodsSet.remove(methodName);
                        processedMethods.add(methodName);
                    }
                }
            }
            Set<String> curSet = new HashSet<>(remainingMethodsSet);
            if (preSet.size() == curSet.size() && preSet.containsAll(curSet)) {
                break;
            }
        }
        Set<String> newFieldsSet = new HashSet<>();
        for (String fieldName : fieldsSet) {
            for (JavaFile javaFile1 : javaFileList) {
                FieldDeclaration field = javaFile1.findFieldDeclaration(fieldName);
                if (field != null) {
                    newFieldsSet.addAll(getRelatedFields(field, javaFile1));
                }
            }
        }
        fieldsSet.addAll(newFieldsSet);
        for (String fieldName : fieldsSet) {
            for (JavaFile javaFile1 : javaFileList) {
                FieldDeclaration field = javaFile1.findFieldDeclaration(fieldName);
                if (field != null) {
                    field.setStatic(true);
                    // rule field
                    for (AnnotationExpr annotationExpr : field.getAnnotations()) {
                        if (annotationExpr.getName().toString().equals("Rule") ||
                                annotationExpr.getName().toString().equals("org.junit.Rule")) {
                            NodeList<AnnotationExpr> ruleFieldAnnotations = field.getAnnotations();
                            int i = 0;
                            for (i = 0; i < ruleFieldAnnotations.size(); i++) {
                                AnnotationExpr ruleFieldAnnotation = ruleFieldAnnotations.get(i);
                                if (ruleFieldAnnotation.getName().toString().equals("Rule")) {
                                    Class clazz = projectClassLoader.loadClass("org.junit.ClassRule");
                                    field.tryAddImportToParentCompilationUnit(clazz);
                                    MarkerAnnotationExpr markerAnnotationExpr = new MarkerAnnotationExpr(
                                            JavaParser.parseName(clazz.getSimpleName()));
                                    field.setAnnotation(i, (AnnotationExpr) markerAnnotationExpr);
                                    break;
                                } else if (ruleFieldAnnotation.getName().toString().equals("org.junit.Rule")) {
                                    MarkerAnnotationExpr markerAnnotationExpr = new MarkerAnnotationExpr(
                                            "org.junit.ClassRule");
                                    field.setAnnotation(i, (AnnotationExpr) markerAnnotationExpr);
                                }
                            }
                            changeFields(field, javaFile);
                        }
                    }
                    changeFields(field, javaFile1);
                }
            }
        }
        for (JavaFile javaFile1 : javaFileList) {
            javaFile1.writeAndReloadCompilationUnit();
        }
    }

    protected static Set<String> getUpperLevelClasses(JavaFile javaFile)
            throws DependencyResolutionRequiredException, IOException {
        // Get all test source files for upper module
        List<Path> wholeTestFiles = wholeTestSources(mavenProject);
        ClassOrInterfaceDeclaration ci = javaFile.getCurCI();
        String currentLevelClassName = ci.getNameAsString();
        Set<String> set = new HashSet<String>();
        for (ClassOrInterfaceType type : ci.getExtendedTypes()) {
            set.add(type.getNameAsString());
            for (final Path anotherFile : wholeTestFiles) {
                if (Files.exists(anotherFile) && FilenameUtils.isExtension(
                        anotherFile.getFileName().toString(), "java")) {
                    if (anotherFile.getFileName().toString().equals(type.getNameAsString() + ".java")) {
                        String newFileName = anotherFile.getFileName().toString();
                        String newFileShortName = newFileName.substring(0, newFileName.lastIndexOf("."));
                        JavaFile newJavaFile = JavaFile.loadFile(anotherFile, classPath,
                                ParserPathManager.compiledPath(anotherFile).getParent(),
                                newFileShortName, "");
                        javaFile.setExtendedJavaFile(newJavaFile);
                        break;
                    }
                }
            }
        }
        return set;
    }

    protected static void addClassAnnotations(JavaFile javaFile, Set<String> fieldsSet, Set<String> methodsSet,
                                              String beforeAnnotation, String afterAnnotation)
            throws DependencyResolutionRequiredException, ClassNotFoundException {
        // method
        List<MethodDeclaration> methods = javaFile.findMethodWithAnnotations(beforeAnnotation);
        String keywordInAfterAnnotation = afterAnnotation.substring(afterAnnotation.lastIndexOf(".") + 1);
        List<MethodDeclaration> existingMethods = javaFile.findMethodWithAnnotations(keywordInAfterAnnotation);
        boolean exist = false;
        if (existingMethods.size() > 0) {
            exist = true;
        }
        for (MethodDeclaration method : methods) {
            if (method != null) {
                method.setStatic(true);
                NodeList<AnnotationExpr> methodAnnotations = method.getAnnotations();
                NodeList<AnnotationExpr> newAnnotations = new NodeList<>();
                int i = 0;
                for (i = 0; i < methodAnnotations.size(); i++) {
                    AnnotationExpr beforeMethodAnnotation = methodAnnotations.get(i);
                    if (beforeMethodAnnotation.getName().toString().equals(beforeAnnotation)) {
                        if (!exist) {
                            Class clazz = projectClassLoader.loadClass(afterAnnotation);
                            method.tryAddImportToParentCompilationUnit(clazz);
                            MarkerAnnotationExpr markerAnnotationExpr = new MarkerAnnotationExpr(
                                    JavaParser.parseName(clazz.getSimpleName()));
                            newAnnotations.add((AnnotationExpr) markerAnnotationExpr);
                        }
                    } else if (beforeMethodAnnotation.getName().toString().equals("Override")) {
                        continue;
                    } else {
                        newAnnotations.add(methodAnnotations.get(i));
                    }
                }
                method.setAnnotations(newAnnotations);
                if (exist) {
                    for (MethodDeclaration existingMethod : existingMethods) {
                        BlockStmt existingBs = existingMethod.getBody().get().asBlockStmt();
                        BlockStmt bs = method.getBody().get().asBlockStmt();
                        if (beforeAnnotation.contains("Before")) {
                            existingBs.getStatements().addAll(bs.getStatements());
                        } else if (beforeAnnotation.contains("After")){
                            existingBs.getStatements().addAll(0, bs.getStatements());
                        }
                        existingMethod.setBody(existingBs);
                        for (ReferenceType rt: method.getThrownExceptions()) {
                            if (!existingMethod.getThrownExceptions().contains(rt)) {
                                existingMethod.addThrownException(rt);
                            }
                        }
                    }
                }
                fieldsSet.addAll(getRelatedFields(method, javaFile, false));
                methodsSet.addAll(getRelatedMethods(method));
                changeMethods(method, javaFile);
            }
        }
        if (exist) {
            for (MethodDeclaration method : methods) {
                javaFile.removeMethod(method);
            }
        }
    }

    protected static Set<String> getRelatedFields(MethodDeclaration md, JavaFile javaFile, boolean flag) {
        Map<String, Range> variableNameMap = new HashMap<>();
        Set<String> set = new HashSet<>();
        Map<VariableDeclarator, Range> localMap = new HashMap<>();
        List<Node> nodesList = new LinkedList<>();
        try {
            getUpperLevelClasses(javaFile);
        } catch (IOException | DependencyResolutionRequiredException ex) {
            ex.printStackTrace();
        }
        for (Statement stmt : md.getBody().get().getStatements()) {
            Queue<Node> nodes = new ArrayDeque<>();
            nodes.add(stmt);
            while(!nodes.isEmpty()) {
                Node node = nodes.peek();
                if (node instanceof VariableDeclarationExpr) {
                    NodeList<VariableDeclarator> variableDeclarators =
                            ((VariableDeclarationExpr) node).asVariableDeclarationExpr().getVariables();
                    for (VariableDeclarator variableDeclarator: variableDeclarators) {
                        Node parentNodeForVD = variableDeclarator.getParentNode().get();
                        while (true) {
                            if (parentNodeForVD.getClass().getName().equals(
                                    "com.github.javaparser.ast.stmt.BlockStmt")) {
                                localMap.put(variableDeclarator, parentNodeForVD.getRange().get());
                                break;
                            }
                            parentNodeForVD = parentNodeForVD.getParentNode().get();
                        }
                    }
                }
                if (node.getChildNodes().size() == 1) {
                    if (node.getChildNodes().get(0).getClass().getName().equals(
                            "com.github.javaparser.ast.expr.SimpleName")) {
                        Node potentialNode = node.getChildNodes().get(0);
                        if (node.getClass().getName().equals("com.github.javaparser.ast.expr.NameExpr")) {
                            String name = ((NameExpr) node).asNameExpr().getNameAsString();
                            if (potentialNode.getRange().isPresent()) {
                                variableNameMap.put(name, potentialNode.getRange().get());
                            }
                        }
                    }
                }
                if (node instanceof FieldAccessExpr) {
                    for (Node ni : node.getChildNodes()) {
                        if (ni.getClass().getName().equals("com.github.javaparser.ast.expr.SimpleName")) {
                            Node potentialNode = ni;
                            String name = ((SimpleName) potentialNode).asString();
                            variableNameMap.put(name, potentialNode.getRange().get());
                        }
                    }
                }
                nodes.poll();
                for (Node node1 : node.getChildNodes()) {
                    nodes.add(node1);
                    nodesList.add(node1);
                }
            }
            for (String variableName : variableNameMap.keySet()) {
                Range variableRange = variableNameMap.get(variableName);
                boolean contain = false;
                for (VariableDeclarator variableDeclarator : localMap.keySet()) {
                    if (variableDeclarator.getNameAsString().equals(variableName)) {
                        Range blockRange = localMap.get(variableDeclarator);
                        if (blockRange.contains(variableRange)) {
                            contain = true;
                            break;
                        }
                    }
                }
                if (contain == false) {
                    set.add(variableName);
                }
            }
            for (Node node : nodesList) {
                if (node instanceof ThisExpr) {
                    if (node.getParentNode().isPresent()) {
                        Node parentNode = ((ThisExpr) node).asThisExpr().getParentNode().get();
                        if (parentNode.toString().equals("MockitoAnnotations.initMocks(this)") ||
                                parentNode.toString().equals("MockitoAnnotations.openMocks(this)")) {
                            parentNode.replace(node, new NameExpr("new " +
                                    javaFile.getCurCI().getName().toString() + "()"));
                        } else if (parentNode.toString().equals("this.getClass()")) {
                            parentNode.replace(node, new NameExpr(
                                    javaFile.getCurCI().getName().toString() + ".class"));
                        } else {
                            for (Node child : parentNode.getChildNodes()) {
                                if (child.getClass().getName().equals("com.github.javaparser.ast.expr.SimpleName")) {
                                    String name = ((SimpleName) child).asString();
                                    set.add(name);
                                }
                            }
			    parentNode.replace(node, new NameExpr(javaFile.getCurCI().getName().toString()));
                        }
                    }
                } else if (flag && node instanceof SuperExpr) {
                    if (node.getParentNode().isPresent()) {
                        Node parentNode = ((SuperExpr) node).asSuperExpr().getParentNode().get();
                        parentNode.replace(node, new NameExpr(
                                javaFile.getExtendedJavaFile().getCurCI().getNameAsString()));
                    }
                }
            }
        }
        return set;
    }

    protected static Set<String> getRelatedFields(FieldDeclaration fd, JavaFile javaFile) {
        Set<String> set = new HashSet<>();
        Map<String, Range> variableNameMap = new HashMap<>();
        Map<VariableDeclarator, Range> localMap = new HashMap<>();
        List<Node> nodesList = new LinkedList<>();
        Queue<Node> nodes = new ArrayDeque<>();
        nodes.add(fd);
        while(!nodes.isEmpty()) {
            Node node = nodes.peek();
            // Assume there are no blocks inside each field declarations
            if (node.getChildNodes().size() == 1) {
                if (node.getChildNodes().get(0).getClass().getName().equals(
                        "com.github.javaparser.ast.expr.SimpleName")) {
                    Node potentialNode = node.getChildNodes().get(0);
                    if (node.getClass().getName().equals("com.github.javaparser.ast.expr.NameExpr")) {
                        String name = ((NameExpr) node).asNameExpr().getNameAsString();
                        variableNameMap.put(name, potentialNode.getRange().get());
                    }
                }
            }
            if (node instanceof FieldAccessExpr) {
                for (Node ni : node.getChildNodes()) {
                    if (ni.getClass().getName().equals("com.github.javaparser.ast.expr.SimpleName")) {
                        Node potentialNode = ni;
                        String name = ((SimpleName) potentialNode).asString();
                        variableNameMap.put(name, potentialNode.getRange().get());
                    }
                }
            }
            nodes.poll();
            for (Node node1 : node.getChildNodes()) {
                nodes.add(node1);
                nodesList.add(node1);
            }
        }
        for (String variableName : variableNameMap.keySet()) {
            // Currently, there are no filters.
            set.add(variableName);
        }
        return set;
    }

    protected static Set<String> getRelatedMethods(MethodDeclaration md) {
        Set<String> set = new HashSet<>();
        for (Statement stmt : md.getBody().get().getStatements()) {
            Queue<Node> nodes = new ArrayDeque<>();
            nodes.add(stmt);
            while(!nodes.isEmpty()) {
                Node node = nodes.peek();
                if (node.getClass().getName().equals("com.github.javaparser.ast.expr.MethodCallExpr")) {
                    set.add(((MethodCallExpr) node).asMethodCallExpr().getName().asString());
                }
                nodes.poll();
                for (Node node1 : node.getChildNodes()) {
                    nodes.add(node1);
                }
            }
        }
        return set;
    }

    protected static void changeMethods(MethodDeclaration md, JavaFile javaFile) {
        for (Statement stmt : md.getBody().get().getStatements()) {
            Queue<Node> nodes = new ArrayDeque<>();
            nodes.add(stmt);
            while(!nodes.isEmpty()) {
                Node node = nodes.peek();
                if (node.getClass().getName().equals("com.github.javaparser.ast.expr.MethodCallExpr")) {
                    if (node.toString().equals("getClass()")) {
                        Node parentNode = ((MethodCallExpr) node).asMethodCallExpr().getParentNode().get();
                        parentNode.replace(node, new NameExpr(javaFile.getCurCI().getName().toString()  + ".class"));
                    } /* else if (node.toString().endsWith(".getClass()")) {
                        Node parentNode = ((MethodCallExpr) node).asMethodCallExpr().getParentNode().get();
                        for (Node child : ((MethodCallExpr) node).asMethodCallExpr().getChildNodes()) {
                            System.out.println(child.toString());
                            System.out.println(child.getClass());// com.github.javaparser.ast.expr.NameExpr
                        }
                        String str = node.toString().replace(".getClass()", ".class");
                        parentNode.replace(node, new NameExpr(str));
                    } */
                }
                nodes.poll();
                for (Node node1 : node.getChildNodes()) {
                    nodes.add(node1);
                }
            }
        }
        return;
    }

    protected static void changeFields(FieldDeclaration fd, JavaFile javaFile) {
        for (VariableDeclarator vd : fd.getVariables()) {
            Queue<Node> nodes = new ArrayDeque<>();
            nodes.add(vd);
            while(!nodes.isEmpty()) {
                Node node = nodes.peek();
                if (node.getClass().getName().equals("com.github.javaparser.ast.expr.MethodCallExpr")) {
                    if (node.toString().equals("getClass()")) {
                        Node parentNode = ((MethodCallExpr) node).asMethodCallExpr().getParentNode().get();
                        parentNode.replace(node, new NameExpr(javaFile.getCurCI().getName().toString()  + ".class"));
                    }
                }
                nodes.poll();
                for (Node node1 : node.getChildNodes()) {
                    nodes.add(node1);
                }
            }
        }
    }


    private static List<Path> wholeTestSources(MavenProject mavenProject) {
        final List<Path> testFiles = new ArrayList<>();
        MavenProject upperProject = mavenProject;
        while (upperProject.hasParent()) {
            if (upperProject.getParent() == null || upperProject.getParent().getBasedir() == null) {
                break;
            }
            upperProject = upperProject.getParent();
        }
        // System.out.println(upperProject.getBuild().getSourceDirectory());
        if (upperProject.getCollectedProjects() != null && upperProject.getCollectedProjects().size() > 0) {
            for (MavenProject mp : upperProject.getCollectedProjects()) {
                try (final Stream<Path> paths = Files.walk(Paths.get(mp.getBuild().getTestSourceDirectory()))) {
                    paths.filter(Files::isRegularFile)
                            .forEach(testFiles::add);
                } catch (Exception ex) {
                    // ex.printStackTrace();
                }
            }
        } else {
            try (final Stream<Path> paths = Files.walk(Paths.get(upperProject.getBuild().getTestSourceDirectory()))) {
                paths.filter(Files::isRegularFile)
                        .forEach(testFiles::add);
            } catch (Exception ex) {
                // ex.printStackTrace();
            }
        }
        return testFiles;
    }
}
