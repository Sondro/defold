package com.dynamo.cr.sceneed.core.internal;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;

import com.dynamo.cr.editor.core.IResourceType;
import com.dynamo.cr.sceneed.core.INodeLoader;
import com.dynamo.cr.sceneed.core.INodeRenderer;
import com.dynamo.cr.sceneed.core.INodeType;
import com.dynamo.cr.sceneed.core.ISceneView;
import com.dynamo.cr.sceneed.core.ISceneView.INodePresenter;
import com.dynamo.cr.sceneed.core.Node;

public class NodeType implements INodeType {

    private final String extension;
    private IConfigurationElement configurationElement;
    private final INodeLoader<Node> loader;
    private final ISceneView.INodePresenter<Node> presenter;
    private final IResourceType resourceType;
    private final Class<?> nodeClass;
    private final List<INodeType> referenceNodeTypes;
    private final String displayGroup;

    public NodeType(String extension,
                    IConfigurationElement configurationElement,
                    INodeLoader<Node> loader,
                    ISceneView.INodePresenter<Node> presenter,
                    IResourceType resourceType,
                    Class<?> nodeClass,
                    String displayGroup) {
        this.extension = extension;
        this.configurationElement = configurationElement;
        this.loader = loader;
        this.presenter = presenter;
        this.resourceType = resourceType;
        this.nodeClass = nodeClass;
        this.referenceNodeTypes = new ArrayList<INodeType>();
        this.displayGroup = displayGroup;
    }

    @Override
    public String getExtension() {
        return this.extension;
    }

    @Override
    public INodeLoader<Node> getLoader() {
        return this.loader;
    }

    @Override
    public INodePresenter<?> getPresenter() {
        return this.presenter;
    }

    @SuppressWarnings("unchecked")
    @Override
    public INodeRenderer<Node> createRenderer() {
        if (configurationElement.getAttribute("renderer") != null) {
            try {
                return (INodeRenderer<Node>) configurationElement.createExecutableExtension("renderer");
            } catch (CoreException e) {
                // TODO: Logging, see case 1331
                e.printStackTrace();
                return null;
            }
        }
        return null;
    }

    @Override
    public IResourceType getResourceType() {
        return this.resourceType;
    }

    @Override
    public Class<?> getNodeClass() {
        return this.nodeClass;
    }

    @Override
    public List<INodeType> getReferenceNodeTypes() {
        return this.referenceNodeTypes;
    }

    @Override
    public String getDisplayGroup() {
        return this.displayGroup;
    }

    public void addReferenceNodeType(INodeType referenceNodeType) {
        this.referenceNodeTypes.add(referenceNodeType);
    }

}
