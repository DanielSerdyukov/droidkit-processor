package droidkit.processor;

import droidkit.annotation.*;
import droidkit.processor.app.ActivityScanner;
import droidkit.processor.app.FragmentScanner;
import droidkit.processor.content.LoaderCallbacksScanner;
import droidkit.processor.sqlite.SQLiteObjectScanner;
import droidkit.processor.view.ViewScanner;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author Daniel Serdyukov
 */
@SupportedAnnotationTypes({
        "droidkit.annotation.SQLiteObject",
        "droidkit.annotation.OnCreateLoader",
        "droidkit.annotation.InjectView",
        "droidkit.annotation.OnClick",
        "droidkit.annotation.OnActionClick"
})
public class AnnotationProcessor extends AbstractProcessor {

    private final Map<String, Factory> mFactories = new HashMap<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        final ProcessingEnv env = new ProcessingEnv(processingEnv);
        mFactories.put(SQLiteObject.class.getName(), new SQLiteObjectFactory(env));
        mFactories.put(OnCreateLoader.class.getName(), new LoaderCallbacksFactory(env));
        final UiComponentFactory uiComponentFactory = new UiComponentFactory(env);
        mFactories.put(InjectView.class.getName(), uiComponentFactory);
        mFactories.put(OnClick.class.getName(), uiComponentFactory);
        mFactories.put(OnActionClick.class.getName(), uiComponentFactory);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return false;
        }
        Observable.from(annotations)
                .filter(new HasFactory(mFactories))
                .flatMap(new GetFactory(mFactories, roundEnv))
                .subscribe(new Action1<ElementScanner>() {
                    @Override
                    public void call(ElementScanner scanner) {
                        scanner.scan();
                    }
                });
        return true;
    }

    //region factories
    interface Factory extends Func2<RoundEnvironment, TypeElement, Observable<ElementScanner>> {

    }

    private static class HasFactory implements Func1<TypeElement, Boolean> {

        private final Map<String, Factory> mFactories;

        private HasFactory(Map<String, Factory> factories) {
            mFactories = factories;
        }

        @Override
        public Boolean call(TypeElement element) {
            return mFactories.containsKey(element.getQualifiedName().toString());
        }

    }

    private static class GetFactory implements Func1<TypeElement, Observable<ElementScanner>> {

        private final Map<String, Factory> mFactories;

        private final RoundEnvironment mRoundEnv;

        private GetFactory(Map<String, Factory> factories, RoundEnvironment roundEnv) {
            mFactories = factories;
            mRoundEnv = roundEnv;
        }

        @Override
        public Observable<ElementScanner> call(TypeElement element) {
            return mFactories.get(element.getQualifiedName().toString()).call(mRoundEnv, element);
        }

    }

    private static class SQLiteObjectFactory implements Factory {

        private final ProcessingEnv mProcessingEnv;

        private SQLiteObjectFactory(ProcessingEnv processingEnv) {
            mProcessingEnv = processingEnv;
        }

        @Override
        public Observable<ElementScanner> call(RoundEnvironment roundEnv, TypeElement element) {
            return Observable.from(roundEnv.getElementsAnnotatedWith(element))
                    .filter(new NotNestedClass(mProcessingEnv))
                    .map(new Func1<Element, ElementScanner>() {
                        @Override
                        public ElementScanner call(Element element) {
                            return new SQLiteObjectScanner(mProcessingEnv, (TypeElement) element);
                        }
                    })
                    .finallyDo(new Action0() {
                        @Override
                        public void call() {
                            SQLiteObjectScanner.brewMetaClass(mProcessingEnv);
                        }
                    });
        }

    }

    private static class LoaderCallbacksFactory implements Factory {

        private final ProcessingEnv mProcessingEnv;

        private LoaderCallbacksFactory(ProcessingEnv processingEnv) {
            mProcessingEnv = processingEnv;
        }

        @Override
        public Observable<ElementScanner> call(RoundEnvironment roundEnv, TypeElement element) {
            return Observable.from(roundEnv.getElementsAnnotatedWith(element))
                    .map(new GetEnclosingElement())
                    .filter(new NotNestedClass(mProcessingEnv))
                    .map(new Func1<Element, ElementScanner>() {
                        @Override
                        public ElementScanner call(Element element) {
                            return new LoaderCallbacksScanner(mProcessingEnv, (TypeElement) element);
                        }
                    });
        }

    }

    private static class UiComponentFactory implements Factory {

        private final Set<Element> mSingleHit = new HashSet<>();

        private final ProcessingEnv mProcessingEnv;

        private UiComponentFactory(ProcessingEnv processingEnv) {
            mProcessingEnv = processingEnv;
        }

        @Override
        public Observable<ElementScanner> call(RoundEnvironment roundEnv, TypeElement element) {
            return Observable.from(roundEnv.getElementsAnnotatedWith(element))
                    .map(new GetEnclosingElement())
                    .filter(new NotNestedClass(mProcessingEnv))
                    .filter(new Func1<Element, Boolean>() {
                        @Override
                        public Boolean call(Element element) {
                            return mSingleHit.add(element);
                        }
                    })
                    .map(new Func1<Element, ElementScanner>() {
                        @Override
                        public ElementScanner call(Element element) {
                            if (mProcessingEnv.isSubtype(element.asType(), "android.app.Activity")) {
                                return new ActivityScanner(mProcessingEnv, (TypeElement) element);
                            } else if (mProcessingEnv.isSubtype(element.asType(), "android.app.Fragment")) {
                                return new FragmentScanner(mProcessingEnv, (TypeElement) element);
                            } else if (mProcessingEnv.isSubtype(element.asType(), "android.view.View")) {
                                return new ViewScanner(mProcessingEnv, (TypeElement) element);
                            }
                            return new ElementScanner(mProcessingEnv, (TypeElement) element) {
                                @Override
                                protected void scan() {
                                    mProcessingEnv.printMessage(Diagnostic.Kind.ERROR, getOrigin(),
                                            "Expected subtype of Activity, Fragment or View");
                                }
                            };
                        }
                    });
        }

    }
    //endregion

    //region filters
    private static class GetEnclosingElement implements Func1<Element, Element> {

        @Override
        public Element call(Element element) {
            return element.getEnclosingElement();
        }

    }

    private static class NotNestedClass implements Func1<Element, Boolean> {

        private final ProcessingEnv mProcessingEnv;

        private NotNestedClass(ProcessingEnv processingEnv) {
            mProcessingEnv = processingEnv;
        }

        @Override
        public Boolean call(Element element) {
            if (ElementKind.PACKAGE != element.getEnclosingElement().getKind()) {
                mProcessingEnv.printMessage(Diagnostic.Kind.ERROR, element,
                        "Annotation not supported for nested class");
                return false;
            }
            return true;
        }

    }
    //endregion

}
