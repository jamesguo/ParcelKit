package kirisame.android.toolset.parcel;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;

import static javax.lang.model.element.ElementKind.CLASS;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.tools.Diagnostic.Kind.NOTE;
import static javax.tools.Diagnostic.Kind.ERROR;


@SupportedAnnotationTypes("kirisame.android.toolset.parcel.ParcelField")
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class ParcelAnnotationProcessor extends AbstractProcessor{

    public static final String DELEGATE_SUFFIX = "$$ParcelDelegate";

    public static final String INJECTOR_SUFFIX = "$$ParcelInjector";

    public static final String PARCEL_TYPE = "android.os.Parcelable";

    public static final String ANDROID_PREFIX = "android.";

    public static final String JAVA_PREFIX = "java.";

    Filer mFiler;

    Elements mElementUtils;

    Types mTypeUtils;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        mFiler = processingEnv.getFiler();
        mElementUtils = processingEnv.getElementUtils();
        mTypeUtils = processingEnv.getTypeUtils();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> supportTypes = new LinkedHashSet<String>();
        supportTypes.add(ParcelField.class.getCanonicalName());
        return supportTypes;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        processingEnv.getMessager().printMessage(NOTE,"start process parcel annotation");

        Map<TypeElement, CodeGenerator> targetClassMap = findAndParseTargets(roundEnv);

        for (Map.Entry<TypeElement, CodeGenerator> entry : targetClassMap.entrySet()) {
            TypeElement typeElement = entry.getKey();
            CodeGenerator injectorCode = entry.getValue();

            try {
                JavaFileObject source = mFiler.
                        createSourceFile(injectorCode.getClassFullNames(), typeElement);
                Writer writer = source.openWriter();
                writer.write(injectorCode.generateJavaSource());
                writer.flush();
                writer.close();
            } catch (IOException e) {
                error(typeElement, "Unable to write injector for type %s: %s", typeElement, e.getMessage());
            }
        }

        return true;
    }

    private Map<TypeElement, CodeGenerator> findAndParseTargets(RoundEnvironment env) {
        Map<TypeElement, CodeGenerator> targetClassMap = new LinkedHashMap<TypeElement, CodeGenerator>();

        for (Element element : env.getElementsAnnotatedWith(ParcelField.class)) {
            try {
                parseParcelField(element, targetClassMap);
            } catch (Exception e) {
                StringWriter stackTrace = new StringWriter();
                e.printStackTrace(new PrintWriter(stackTrace));

                error(element, "Unable to generate parcel injector for @ParcelField.\n\n%s", stackTrace);
            }
        }

        return targetClassMap;
    }

    private void parseParcelField(Element element, Map<TypeElement, CodeGenerator> targetClassMap) {

        // we shall check all exception in code in a single execution , or user will have to run several times to find where they get opps
        boolean hasError = false;

        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
        TypeMirror enclosingElementType = getElementType(enclosingElement);
        TypeMirror elementType = getElementType(element);

        hasError |= isInaccessibleViaGeneratedCode(ParcelField.class, "fields", element);
        hasError |= isBindingInWrongPackage(ParcelField.class, element);

        if (hasError) {
            throw new RuntimeException("has error");
        }

        /**
         *  now we create a {@link CodeGenerator} connecting with a enclosing element
         */
        CodeGenerator generator = getOrCreateTargetClass(targetClassMap, enclosingElement);

        /**
         *  now we create a {@link CodeFragment} connecting with a field of a Parcelable class
         */
        String name = element.getSimpleName().toString();
        String type = elementType.toString();

        note("elementName=%s,elementType=%s",name,type);

        generator.addCodeFragment(name, type);

    }

    private TypeMirror getElementType(Element element) {
        // TypeMirror represents a type in java
        TypeMirror elementType = element.asType();
        // if the type is variable type , use the upper bound
        if (elementType instanceof TypeVariable) {
            TypeVariable typeVariable = (TypeVariable) elementType;
            elementType = typeVariable.getUpperBound();
        }
        return elementType;
    }

    // create code generator
    private CodeGenerator getOrCreateTargetClass(
            Map<TypeElement, CodeGenerator> targetClassMap,
            TypeElement enclosingElement) {

        boolean isParcelable = isParcelable(enclosingElement.asType());
        String suffix = isParcelable ? INJECTOR_SUFFIX : DELEGATE_SUFFIX;

        CodeGenerator codeGenerator = targetClassMap.get(enclosingElement);
        if (codeGenerator == null) {
            String targetClassName = enclosingElement.getQualifiedName().toString();
            String packageName = getPackageName(enclosingElement);
            String className = getClassName(enclosingElement, packageName) + suffix;

            note("enclosingElementHash=%s,targetClassName=%s,packageName=%s,className=%s", enclosingElement.hashCode(), targetClassName, packageName, className);

            codeGenerator = isParcelable?
                    new InjectorGenerator(packageName, className, targetClassName):
                    new DelegateGenerator(packageName, className, targetClassName);
            targetClassMap.put(enclosingElement, codeGenerator);
        }
        return codeGenerator;
    }

    private boolean isPrimitiveType(TypeMirror typeMirror) {
        return typeMirror instanceof PrimitiveType;
    }

    private boolean isParcelable(TypeMirror typeMirror) {
        return isSubtypeOfType(typeMirror, PARCEL_TYPE);
    }


    // check the element modifier type and visibility in a single execution
    private boolean isInaccessibleViaGeneratedCode(Class<? extends Annotation> annotationClass,
                                                   String targetThing, Element element) {
        boolean hasError = false;
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

        // Verify method modifiers.
        Set<Modifier> modifiers = element.getModifiers();
        if (modifiers.contains(PRIVATE) || modifiers.contains(STATIC)) {
            error(element, "@%s %s must not be private or static. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName());
            hasError = true;
        }

        // Verify containing type.
        if (enclosingElement.getKind() != CLASS) {
            error(enclosingElement, "@%s %s may only be contained in classes. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName());
            hasError = true;
        }

        // Verify containing class visibility is not private.
        if (enclosingElement.getModifiers().contains(PRIVATE)) {
            error(enclosingElement, "@%s %s may not be contained in private classes. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName());
            hasError = true;
        }

        return hasError;
    }

    // the code of this method is pasted from butter-knife , i'm not sure why we have to run this check
    private boolean isBindingInWrongPackage(Class<? extends Annotation> annotationClass,
                                            Element element) {
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
        String qualifiedName = enclosingElement.getQualifiedName().toString();

        if (qualifiedName.startsWith(ANDROID_PREFIX)) {
            error(element, "@%s-annotated class incorrectly in Android framework package. (%s)",
                    annotationClass.getSimpleName(), qualifiedName);
            return true;
        }
        if (qualifiedName.startsWith(JAVA_PREFIX)) {
            error(element, "@%s-annotated class incorrectly in Java framework package. (%s)",
                    annotationClass.getSimpleName(), qualifiedName);
            return true;
        }

        return false;
    }


    // code is pasted from butter-knife . deal with it later
    private boolean isSubtypeOfType(TypeMirror typeMirror, String otherType) {
        if (otherType.equals(typeMirror.toString())) {
            return true;
        }
        if (!(typeMirror instanceof DeclaredType)) {
            return false;
        }
        DeclaredType declaredType = (DeclaredType) typeMirror;
        List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
        if (typeArguments.size() > 0) {
            StringBuilder typeString = new StringBuilder(declaredType.asElement().toString());
            typeString.append('<');
            for (int i = 0; i < typeArguments.size(); i++) {
                if (i > 0) {
                    typeString.append(',');
                }
                typeString.append('?');
            }
            typeString.append('>');
            if (typeString.toString().equals(otherType)) {
                return true;
            }
        }
        Element element = declaredType.asElement();
        if (!(element instanceof TypeElement)) {
            return false;
        }
        TypeElement typeElement = (TypeElement) element;
        TypeMirror superType = typeElement.getSuperclass();
        if (isSubtypeOfType(superType, otherType)) {
            return true;
        }
        for (TypeMirror interfaceType : typeElement.getInterfaces()) {
            if (isSubtypeOfType(interfaceType, otherType)) {
                return true;
            }
        }
        return false;
    }

    private void error(Element element,String message,Object... args) {
        if (args.length > 0) {
            message = String.format(message, args);
        }
        processingEnv.getMessager().printMessage(ERROR, message, element);
    }

    private void note(String message,Object... args) {
        processingEnv.getMessager().printMessage(NOTE, String.format(message, args));
    }

    private String getPackageName(TypeElement type) {
        return mElementUtils.getPackageOf(type).getQualifiedName().toString();
    }

    private static String getClassName(TypeElement type, String packageName) {
        int packageLen = packageName.length() + 1;
        return type.getQualifiedName().toString().substring(packageLen).replace('.', '$');
    }
}
