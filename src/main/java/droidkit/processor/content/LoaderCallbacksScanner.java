package droidkit.processor.content;

import com.squareup.javapoet.*;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import droidkit.processor.ElementScanner;
import droidkit.processor.ProcessingEnv;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementScanner7;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * @author Daniel Serdyukov
 */
public class LoaderCallbacksScanner extends ElementScanner {

    private final List<CodeBlock> mOnCreateCases = new ArrayList<>();

    private final List<CodeBlock> mOnLoadCases = new ArrayList<>();

    private final List<CodeBlock> mOnResetCases = new ArrayList<>();

    public LoaderCallbacksScanner(ProcessingEnv env, TypeElement originType) {
        super(env, originType);
    }

    @Override
    protected void scan() {
        getOrigin().accept(new ElementScanner7<Void, Void>() {
            @Override
            public Void visitExecutable(ExecutableElement method, Void aVoid) {
                for (final CallbackVisitor visitor : CallbackVisitor.SUPPORTED) {
                    final int[] loaderIds = visitor.getLoaderIds(method);
                    if (loaderIds.length > 0) {
                        getEnv().<JCTree.JCMethodDecl>getTree(method).mods.flags &= ~Flags.PRIVATE;
                    }
                    for (final int loaderId : loaderIds) {
                        visitor.visit(LoaderCallbacksScanner.this, method, loaderId);
                    }
                }
                return super.visitExecutable(method, aVoid);
            }
        }, null);
        brewJava();
    }

    void addOnCreateLoaderCase(CodeBlock caseBlock) {
        mOnCreateCases.add(caseBlock);
    }

    void addOnLoadFinishedCase(CodeBlock caseBlock) {
        mOnLoadCases.add(caseBlock);
    }

    void addOnLoaderResetCase(CodeBlock caseBlock) {
        mOnResetCases.add(caseBlock);
    }

    void unexpectedMethodSignature(ExecutableElement method) {
        getEnv().printMessage(Diagnostic.Kind.ERROR, method, "Unexpected method signature");
    }

    private void brewJava() {
        final TypeSpec typeSpec = TypeSpec.classBuilder(getOrigin().getSimpleName() + "$LC")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "$S", "unchecked")
                        .build())
                .addSuperinterface(ClassName.get("android.app", "LoaderManager", "LoaderCallbacks"))
                .addOriginatingElement(getOrigin())
                .addField(targetRef())
                .addMethod(constructor())
                .addMethod(onCreateLoader())
                .addMethod(onLoadFinished())
                .addMethod(onLoaderReset())
                .build();
        final JavaFile javaFile = JavaFile.builder(getOrigin().getEnclosingElement().toString(), typeSpec)
                .addFileComment(AUTO_GENERATED_FILE)
                .build();
        try {
            final JavaFileObject sourceFile = getEnv().createSourceFile(
                    javaFile.packageName + "." + typeSpec.name, getOrigin());
            try (final Writer writer = new BufferedWriter(sourceFile.openWriter())) {
                javaFile.writeTo(writer);
            }
        } catch (IOException e) {
            Logger.getGlobal().throwing(LoaderCallbacksScanner.class.getName(), "brewJava", e);
        }
    }

    //region implementation
    private FieldSpec targetRef() {
        return FieldSpec.builder(ParameterizedTypeName.get(
                        ClassName.get(Reference.class),
                        ClassName.get(getOrigin())),
                "mTargetRef", Modifier.PRIVATE, Modifier.FINAL
        ).build();
    }

    private MethodSpec constructor() {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get(getOrigin()), "referent")
                .addStatement("mTargetRef = new $T<>(referent)", ClassName.get(WeakReference.class))
                .build();
    }

    private MethodSpec onCreateLoader() {
        final MethodSpec.Builder builder = MethodSpec.methodBuilder("onCreateLoader")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get("android.content", "Loader"))
                .addParameter(TypeName.INT, "loaderId")
                .addParameter(ClassName.get("android.os", "Bundle"), "args")
                .addStatement("final $T referent = mTargetRef.get()", ClassName.get(getOrigin()))
                .beginControlFlow("if (referent != null)")
                .beginControlFlow("switch (loaderId)");
        for (final CodeBlock caseBlock : mOnCreateCases) {
            builder.addCode(caseBlock);
        }
        return builder
                .addCode(CodeBlock.builder()
                        .add("default:\n").indent()
                        .addStatement("return null").unindent()
                        .build())
                .endControlFlow()
                .endControlFlow()
                .addStatement("throw new $T($S)", ClassName.get(IllegalStateException.class),
                        "It seems, LoaderManager leaked")
                .build();
    }

    private MethodSpec onLoadFinished() {
        final MethodSpec.Builder builder = MethodSpec.methodBuilder("onLoadFinished")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get("android.content", "Loader"), "loader")
                .addParameter(ClassName.get(Object.class), "result")
                .addStatement("final $T referent = mTargetRef.get()", ClassName.get(getOrigin()))
                .beginControlFlow("if (referent != null)")
                .beginControlFlow("switch (loader.getId())");
        for (final CodeBlock caseBlock : mOnLoadCases) {
            builder.addCode(caseBlock);
        }
        return builder.endControlFlow()
                .endControlFlow()
                .build();
    }

    private MethodSpec onLoaderReset() {
        final MethodSpec.Builder builder = MethodSpec.methodBuilder("onLoaderReset")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get("android.content", "Loader"), "loader")
                .addStatement("final $T referent = mTargetRef.get()", ClassName.get(getOrigin()))
                .beginControlFlow("if (referent != null)")
                .beginControlFlow("switch (loader.getId())");
        for (final CodeBlock caseBlock : mOnResetCases) {
            builder.addCode(caseBlock);
        }
        return builder.endControlFlow()
                .endControlFlow()
                .build();
    }
    //endregion

}
