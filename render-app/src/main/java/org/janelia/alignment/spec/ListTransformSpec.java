package org.janelia.alignment.spec;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;

/**
 * List of transform specifications.
 *
 * NOTE: The {@link org.janelia.alignment.json.TransformSpecAdapter} implementation handles
 * polymorphic deserialization for this class and is tightly coupled to it's implementation here.
 * The adapter will need to be modified any time attributes of this class are modified.
 *
 * @author Eric Trautman
 */
public class ListTransformSpec extends TransformSpec {

    public static final String TYPE = "list";
    public static final String SPEC_LIST_ELEMENT_NAME = "specList";

    private List<TransformSpec> specList;

    public ListTransformSpec() {
        this(null, null);
    }

    public ListTransformSpec(final String id,
                             final TransformSpecMetaData metaData) {
        super(id, TYPE, metaData);
        this.specList = new ArrayList<TransformSpec>();
    }

    public TransformSpec getSpec(final int index) {
        return specList.get(index);
    }

    public void addSpec(final TransformSpec spec) {
        specList.add(spec);
        removeInstance();
    }

    public void setSpec(final int index,
                        final TransformSpec spec) {
        specList.set(index, spec);
        removeInstance();
    }

    public void removeLastSpec() {
        if (specList.size() > 0) {
            specList.remove(specList.size() - 1);
            removeInstance();
        }
    }

    public void addAllSpecs(final List<TransformSpec> specs) {
        this.specList.addAll(specs);
        removeInstance();
    }

    public int size() {
        return specList.size();
    }

    public void removeNullSpecs() {
        TransformSpec spec;
        for (final Iterator<TransformSpec> i = specList.iterator(); i.hasNext();) {
            spec = i.next();
            if (spec == null) {
                i.remove();
            }
        }
        removeInstance();
    }

    @Override
    public boolean isFullyResolved()
            throws IllegalStateException {
        boolean allSpecsResolved = true;
        for (final TransformSpec spec : specList) {
            if (spec == null) {
                throw new IllegalStateException("A null spec is part of the transform spec list with id '" + getId() +
                                                "'.  Check for an extraneous comma at the end of the list.");
            }
            if (! spec.isFullyResolved()) {
                allSpecsResolved = false;
                break;
            }
        }
        return allSpecsResolved;
    }

    @Override
    public void addUnresolvedIds(final Set<String> unresolvedIds) {
        for (final TransformSpec spec : specList) {
            spec.addUnresolvedIds(unresolvedIds);
        }
    }

    @Override
    public void resolveReferences(final Map<String, TransformSpec> idToSpecMap) {
        for (final TransformSpec spec : specList) {
            spec.resolveReferences(idToSpecMap);
        }
    }

    @Override
    public void flatten(final ListTransformSpec flattenedList) throws IllegalStateException {
        for (final TransformSpec spec : specList) {
            spec.flatten(flattenedList);
        }
    }

    @SuppressWarnings("unchecked")
    public CoordinateTransformList<CoordinateTransform> getInstanceAsList()
            throws IllegalArgumentException {
        return (CoordinateTransformList<CoordinateTransform>) super.getInstance();
    }

    @Override
    protected CoordinateTransform buildInstance()
            throws IllegalArgumentException {
        final CoordinateTransformList<CoordinateTransform> ctList = new CoordinateTransformList<CoordinateTransform>();
        for (final TransformSpec spec : specList) {
            ctList.add(spec.buildInstance());
        }
        return ctList;
    }
}
