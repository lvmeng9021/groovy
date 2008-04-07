/*
 * Copyright 2003-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.ast;

import groovy.lang.Binding;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.util.*;

/**
 * Represents a module, which consists typically of a class declaration
 * but could include some imports, some statements and multiple classes
 * intermixed with statements like scripts in Python or Ruby
 *
 * @author Jochen Theodorou
 * @author <a href="mailto:james@coredevelopers.net">James Strachan</a>
 * @version $Revision$
 */
public class ModuleNode extends ASTNode implements Opcodes {

    private BlockStatement statementBlock = new BlockStatement();
    List classes = new LinkedList();
    private List methods = new ArrayList();
    private List imports = new ArrayList();
    private List importPackages = new ArrayList();
    private Map importIndex = new HashMap();
    private Map staticImportAliases = new HashMap();
    private Map staticImportFields = new LinkedHashMap();
    private Map staticImportClasses = new LinkedHashMap();
    private CompileUnit unit;
    private String packageName;
    private String description;
    private boolean createClassForStatements = true;
    private transient SourceUnit context;
    private boolean importsResolved = false;
    private static final String[] EMPTY_STRING_ARRAY = new String[] { /* class names, not qualified */ };

    /**
     * The ASTSingleNodeTransformations to be applied to the source unit
     */
    private Map<CompilePhase, Map<ASTSingleNodeTransformation, ASTNode>> singleNodeTransformInstances;
    private Map<CompilePhase, List<CompilationUnit.SourceUnitOperation>> sourceUnitTransforms;
    private Map<CompilePhase, List<CompilationUnit.PrimaryClassNodeOperation>> classTransforms;


    public ModuleNode (SourceUnit context ) {
        this.context = context;
        initTransforms();
    }

    public ModuleNode (CompileUnit unit) {
        this.unit = unit;
        initTransforms();
    }

    private void initTransforms() {
        singleNodeTransformInstances = new EnumMap<CompilePhase, Map<ASTSingleNodeTransformation, ASTNode>>(CompilePhase.class);
        for (CompilePhase phase : CompilePhase.values()) {
            singleNodeTransformInstances.put(phase, new HashMap<ASTSingleNodeTransformation, ASTNode>());
        }
        sourceUnitTransforms = new EnumMap<CompilePhase, List<CompilationUnit.SourceUnitOperation>>(CompilePhase.class);
        for (CompilePhase phase : CompilePhase.values()) {
            sourceUnitTransforms.put(phase, new ArrayList<CompilationUnit.SourceUnitOperation>());
        }
        classTransforms = new EnumMap<CompilePhase, List<CompilationUnit.PrimaryClassNodeOperation>>(CompilePhase.class);
        for (CompilePhase phase : CompilePhase.values()) {
            classTransforms.put(phase, new ArrayList<CompilationUnit.PrimaryClassNodeOperation>());
        }
    }

    public BlockStatement getStatementBlock() {
        return statementBlock;
    }

    public List getMethods() {
        return methods;
    }

    public List getClasses() {
        if (createClassForStatements && (!statementBlock.isEmpty() || !methods.isEmpty())) {
            ClassNode mainClass = createStatementsClass();
            createClassForStatements = false;
            classes.add(0, mainClass);
            mainClass.setModule(this);
            addToCompileUnit(mainClass);
        }
        return classes;
    }

    public List getImports() {
        return imports;
    }

    public List getImportPackages() {
        return importPackages;
    }

    /**
     * @return the class name for the given alias or null if none is available
     */
    public ClassNode getImport(String alias) {
        return (ClassNode) importIndex.get(alias);
    }

    public void addImport(String alias, ClassNode type) {
        imports.add(new ImportNode(type, alias));
        importIndex.put(alias, type);
    }

    public String[]  addImportPackage(String packageName) {
        importPackages.add(packageName);
        return EMPTY_STRING_ARRAY;
    }

    public void addStatement(Statement node) {
        statementBlock.addStatement(node);
    }

    public void addClass(ClassNode node) {
        classes.add(node);
        node.setModule(this);
        addToCompileUnit(node);
    }

    /**
     * @param node
     */
    private void addToCompileUnit(ClassNode node) {
        // register the new class with the compile unit
        if (unit != null) {
            unit.addClass(node);
        }
    }

    public void addMethod(MethodNode node) {
        methods.add(node);
    }

    public void visit(GroovyCodeVisitor visitor) {
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }
    
    public boolean hasPackageName(){
        return this.packageName != null;
    }

    public SourceUnit getContext() {
        return context;
    }

    /**
     * @return the underlying character stream description
     */
    public String getDescription() {
        if( context != null )
        {
            return context.getName();
        }
        else
        {
            return this.description;
        }
    }

    public void setDescription(String description) {
        // DEPRECATED -- context.getName() is now sufficient
        this.description = description;
    }

    public CompileUnit getUnit() {
        return unit;
    }

    void setUnit(CompileUnit unit) {
        this.unit = unit;
    }

    protected ClassNode createStatementsClass() {
        String name = getPackageName();
        if (name == null) {
            name = "";
        }
        // now lets use the file name to determine the class name
        if (getDescription() == null) {
            throw new RuntimeException("Cannot generate main(String[]) class for statements when we have no file description");
        }
        name += extractClassFromFileDescription();

        String baseClassName = null;
        if (unit != null) baseClassName = unit.getConfig().getScriptBaseClass();
        ClassNode baseClass = null;
        if (baseClassName!=null) {
            baseClass = ClassHelper.make(baseClassName);
        }
        if (baseClass == null) {
            baseClass = ClassHelper.SCRIPT_TYPE;
        }
        ClassNode classNode = new ClassNode(name, ACC_PUBLIC, baseClass);
        classNode.setScript(true);
        classNode.setScriptBody(true);

        // return new Foo(new ShellContext(args)).run()
        classNode.addMethod(
            new MethodNode(
                "main",
                ACC_PUBLIC | ACC_STATIC,
                ClassHelper.VOID_TYPE,
                new Parameter[] { new Parameter(ClassHelper.STRING_TYPE.makeArray(), "args")},
                ClassNode.EMPTY_ARRAY,
                new ExpressionStatement(
                    new MethodCallExpression(
                        new ClassExpression(ClassHelper.make(InvokerHelper.class)),
                        "runScript",
                        new ArgumentListExpression(
                                new ClassExpression(classNode),
                                new VariableExpression("args"))))));

        classNode.addMethod(
            new MethodNode("run", ACC_PUBLIC, ClassHelper.OBJECT_TYPE, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, statementBlock));

        classNode.addConstructor(ACC_PUBLIC, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, new BlockStatement());
        Statement stmt = new ExpressionStatement(
                        new MethodCallExpression(
                            new VariableExpression("super"),
            				"setBinding",
            				new ArgumentListExpression(
                                        new VariableExpression("context"))));

        classNode.addConstructor(
            ACC_PUBLIC,
            new Parameter[] { new Parameter(ClassHelper.make(Binding.class), "context")},
			ClassNode.EMPTY_ARRAY,
            stmt);

        for (Iterator iter = methods.iterator(); iter.hasNext();) {
            MethodNode node = (MethodNode) iter.next();
            int modifiers = node.getModifiers();
            if ((modifiers & ACC_ABSTRACT) != 0) {
                throw new RuntimeException(
                    "Cannot use abstract methods in a script, they are only available inside classes. Method: "
                        + node.getName());
            }
            // br: the old logic seems to add static to all def f().... in a script, which makes enclosing
            // inner classes (including closures) in a def function difficult. Comment it out.
            node.setModifiers(modifiers /*| ACC_STATIC*/);

            classNode.addMethod(node);
        }
        return classNode;
    }

    protected String extractClassFromFileDescription() {
        // lets strip off everything after the last .
        String answer = getDescription();
        int idx = answer.lastIndexOf('.');
        if (idx > 0) {
            answer = answer.substring(0, idx);
        }
        // new lets trip the path separators
        idx = answer.lastIndexOf('/');
        if (idx >= 0) {
            answer = answer.substring(idx + 1);
        }
        idx = answer.lastIndexOf(File.separatorChar);
        if (idx >= 0) {
            answer = answer.substring(idx + 1);
        }
        return answer;
    }

    public boolean isEmpty() {
        return classes.isEmpty() && statementBlock.getStatements().isEmpty();
    }
    
    public void sortClasses(){
    	if (isEmpty()) return;
    	List classes = getClasses();
    	LinkedList sorted = new LinkedList();
    	int level=1;
    	while (!classes.isEmpty()) {
	    	for (Iterator cni = classes.iterator(); cni.hasNext();) {
				ClassNode cn = (ClassNode) cni.next();
				ClassNode sn = cn;
				for (int i=0; sn!=null && i<level; i++) sn = sn.getSuperClass();
				if (sn!=null && sn.isPrimaryClassNode()) continue;
				cni.remove();
				sorted.addLast(cn);
			}
	    	level++;
    	}
    	this.classes = sorted;
    }

    public boolean hasImportsResolved() {
        return importsResolved;
    }

    public void setImportsResolved(boolean importsResolved) {
        this.importsResolved = importsResolved;
    }

    public Map getStaticImportAliases() {
        return staticImportAliases;
    }

    public Map getStaticImportClasses() {
        return staticImportClasses;
    }

    public Map getStaticImportFields() {
        return staticImportFields;
    }

    public void addStaticMethodOrField(ClassNode type, String fieldName, String alias) {
        staticImportAliases.put(alias, type);
        staticImportFields.put(alias, fieldName);
    }

    public void addStaticImportClass(String name, ClassNode type) {
        staticImportClasses.put(name, type);
    }

    public void addSingleNodeTransform(ASTSingleNodeTransformation transform, ASTNode node) {
        GroovyASTTransformation annotation = transform.getClass().getAnnotation(GroovyASTTransformation.class);
        singleNodeTransformInstances.get(annotation.phase()).put(transform, node);
    }

    public Map<ASTSingleNodeTransformation, ASTNode> getSingleNodeTransforms(CompilePhase phase) {
        return singleNodeTransformInstances.get(phase);
    }

    public void addSourceUnitOperation(CompilationUnit.SourceUnitOperation transform) {
        GroovyASTTransformation annotation = transform.getClass().getAnnotation(GroovyASTTransformation.class);
        sourceUnitTransforms.get(annotation.phase()).add(transform);
    }

    public List<CompilationUnit.SourceUnitOperation> getSourceUnitTransforms(CompilePhase phase) {
        return sourceUnitTransforms.get(phase);
    }

    public void addClassTransform(CompilationUnit.PrimaryClassNodeOperation transform) {
        GroovyASTTransformation annotation = transform.getClass().getAnnotation(GroovyASTTransformation.class);
        classTransforms.get(annotation.phase()).add(transform);
    }

    public List<CompilationUnit.PrimaryClassNodeOperation> getClassTransforms(CompilePhase phase) {
        return classTransforms.get(phase);
    }

}
