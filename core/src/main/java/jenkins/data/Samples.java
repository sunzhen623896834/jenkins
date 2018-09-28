package jenkins.data;

import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.model.Describable;
import hudson.model.Descriptor;
import jenkins.data.tree.TreeNode;
import jenkins.data.tree.Mapping;
import jenkins.data.tree.Scalar;
import jenkins.model.Jenkins;
import org.jvnet.tiger_types.Types;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Arrays;

/**
 * Here is how the Data API gets consumed by plugin devs.
 *
 * <ul>
 *     <li>How to write a custom serializer
 *
 * @author Kohsuke Kawaguchi
 */
public class Samples {
    public static abstract class Fruit implements ExtensionPoint, Describable<Fruit> {
        protected String name;
        protected Fruit(String name) { this.name = name; }

        public Descriptor<Fruit> getDescriptor() {
            return Jenkins.getInstance().getDescriptor(getClass());
        }
    }

    public static class FruitDescriptor extends Descriptor<Fruit> {}

    /**
     * Implicit inline model where in-memory format and the data format is identical.
     */
    public static class Apple extends Fruit {
        private int seeds;
        @DataBoundConstructor public Apple(int seeds) {
            super("Apple");
            this.seeds = seeds;
        }
        @Extension
        public static final class DescriptorImpl extends FruitDescriptor {}
    }

    /**
     * Custom binder falling back to the default reflection-based reader
     */
    public static class Banana extends Fruit {
        private boolean yellow;
        @DataBoundConstructor
        public Banana(boolean yellow) {
            super("Banana");
            this.yellow = yellow;
        }

        public boolean isYellow() {
            return yellow;
        }

        @Extension
        public static final class DescriptorImpl extends FruitDescriptor {}
    }

    @Describes(Banana.class)
    public class BananaModel extends CustomDataModel<Banana> {
        public BananaModel() {
            super(Banana.class,
                    parameter("ripe",boolean.class));
        }

        @Override
        public TreeNode write(Banana object, DataContext context) {
            Mapping m = new Mapping();
            m.put("ripe",object.yellow);
            return m;
        }

        @Override
        public Banana read(TreeNode input, DataContext context) throws IOException {
            Mapping m = input.asMapping();
            m.put("yellow",m.get("ripe"));
            DataModel<Banana> std = DataModel.byReflection(Banana.class);
            return std.read(input, context);
        }
    }

    /**
     * Custom serializer from scratch, no delegation to default.
     */
    public static class Cherry extends Fruit {
        private String color;

        public Cherry(String c) {
            super("Cherry");
            this.color = c;
        }

        public String color() { // don't need to be following convention
            return color;
        }

        @Extension
        public static final class DescriptorImpl extends FruitDescriptor {}
    }

    @Describes(Cherry.class)
    public class CherryModel extends CustomDataModel<Cherry> {
        public CherryModel() {
            // TODO: in this example, cherry binds to a scalar, so how do you go about parameters?
            super(Cherry.class);
        }

        @Override
        public TreeNode write(Cherry object, DataContext context) {
            return new Scalar(object.color());
        }

        @Override
        public Cherry read(TreeNode input, DataContext context) throws IOException {
            return new Cherry(input.asScalar().getValue());
        }
    }

    /**
     * Example where 'contract' is defined elsewhere explicitly as a separate resource class
     */
    public static class Durian extends Fruit implements APIExportable {
        private float age;

        // some other gnary fields that you don't want to participate in the format

        public Durian(float age) {
            super("Durian");
            this.age = age;
        }

        // lots of gnary behaviours

        public DurianResource toResource() {
            return new DurianResource(age>30.0f);
        }

        @Extension
        public static final class DescriptorImpl extends FruitDescriptor {}
    }

    /**
     * Model object that's defined as contract. This is the class that gets data-bound.
     */
    public static class DurianResource implements APIResource {
        private boolean smelly;

        @DataBoundConstructor
        public DurianResource(boolean smelly) {
            this.smelly = smelly;
        }

        public boolean isSmelly() {
            return smelly;
        }

        public Durian toModel() {
            return new Durian(smelly?45.0f:15.0f);
        }

        // no behavior
    }

    // Jesse sees this more as a convenience sugar, not a part of the foundation,
    // in which case helper method like this is preferrable over interfaces that 'invade' model objects
    //
    // Kohsuke notes that, channeling Antonio & James N & co, the goal is to make the kata more explicit,
    // so this would go against that.
    //
    // either way, we'd like to establish that these can be implemented as sugar
    @Describes(Durian.class)
    public static DataModel<Durian> durianBinder() {
        return DataModel.byTranslation(DurianResource.class,
                dr -> new Durian(dr.smelly ? 45 : 15),
                d -> new DurianResource(d.age > 30));
    }


    /**
     * Variant of a Durian example in the form closer to Antonio's original proposal.
     *
     * This has the effect of making the idiom more explicit.
     */
    public static class Eggfruit extends Fruit implements APIExportable<EggfruitResource> {
        private float age;

        // some other gnary fields that you don't want to participate in the format

        public Eggfruit(float age) {
            super("Eggfruit");
            this.age = age;
        }

        // lots of gnary behaviours

        public EggfruitResource toResource() {
            return new EggfruitResource(age>30.0f);
        }

        @Extension
        public static final class DescriptorImpl extends FruitDescriptor {}
    }

    /**
     * Model object that's defined as contract. This is the class that gets data-bound.
     */
    public static class EggfruitResource implements APIResource {
        private boolean smelly;

        @DataBoundConstructor
        public EggfruitResource(boolean smelly) {
            this.smelly = smelly;
        }

        public boolean isSmelly() {
            return smelly;
        }

        public Eggfruit toModel() {
            return new Eggfruit(smelly?45.0f:15.0f);
        }

        // no behavior
    }

    /**
     * This would be a part of the system, not a part of the user-written code.
     * It's a bit of sugar
     */
    @Extension
    public static class APIExportableModelFactory implements DataModelFactory {
        @Override
        public DataModel find(final Type type) {
            Class<Object> clazz = Types.erasure(Types.getTypeArgument(type, 0));
            if (APIExportable.class.isAssignableFrom(clazz)) {
                return new TranslatedModel(clazz);
            }
            return null;
        }

        private static class TranslatedModel<T extends APIExportable<U>,U extends APIResource> extends CustomDataModel<T> {
            private final Class<U> u;
            private final DataModel<U> um;

            public TranslatedModel(Class<T> type) {
                super(type);
                Type t = Types.getBaseClass(type, APIExportable.class);
                Type u = Types.getTypeArgument(t, 0);
                this.u = Types.erasure(u);
                um = DataModel.byReflection(this.u);
            }

            @Override
            public TreeNode write(T object, DataContext context) {
                U r = object.toResource();
                return um.write(r, context);
            }

            @Override
            public T read(TreeNode input, DataContext context) throws IOException {
                return (T)um.read(input, context).toModel();
            }
        }
    }



//    public static class GitSCM extends Fruit {
//        private List<UserRemoteConfig> userRemoteConfigs;
//        private transient List<RemoteConfig> remoteRepositories;
//    }
//
//    public class UserRemoteConfig extends AbstractDescribableImpl<UserRemoteConfig> {
//        private String name;
//        private String refspec;
//        private String url;
//        private String credentialsId;
//    }



    /**
     * Read from stdin and construct model object
     */
    public void cliReaderSample() throws IOException {
            VersionedEnvelope<Fruit> envelope = new JsonSerializer().read(Fruit.class, System.in);
            for (Fruit res : envelope.getData()) {
                System.out.println(res);
            }

//            VersionedResource input = JSONCLICommandHelper.readInput(stdin);
//            for (Object res : (Collection) input.getData()) {
//                DomainCredentials domainCredentials = (DomainCredentials) ((APIResource) res).toModel();
//                Domain domain = domainCredentials.getDomain();
//                if (domainCredentials.getDomain().getName() == null) {
//                    domain = Domain.global();
//                }
//                for (Credentials creds : domainCredentials.getCredentials()) {
//                    store.addCredentials(domain, creds);
//                }
//            }
    }

    public void cliWriterExample() throws IOException {
        VersionedEnvelope<Fruit> d = new VersionedEnvelope<>(1, Arrays.asList(
                new Apple(3),
                new Banana(true),
                new Cherry("orange"),
                new Durian(35.0f)
        ));

        new JsonSerializer().write(d, System.out);
    }

    // TODO: does this replace structs for Pipeline?
    // TODO: does this replace CasC?


}
