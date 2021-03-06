/*
 * JBoss by Red Hat
 * Copyright 2006-2009, Red Hat Middleware, LLC, and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.ide.eclipse.freemarker.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.BadPositionCategoryException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.ITypedRegion;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.MatchingCharacterPainter;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.texteditor.ContentAssistAction;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.eclipse.ui.texteditor.MarkerUtilities;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;
import org.jboss.ide.eclipse.freemarker.Plugin;
import org.jboss.ide.eclipse.freemarker.configuration.ConfigurationManager;
import org.jboss.ide.eclipse.freemarker.lang.LexicalConstants;
import org.jboss.ide.eclipse.freemarker.model.Item;
import org.jboss.ide.eclipse.freemarker.model.ItemSet;
import org.jboss.ide.eclipse.freemarker.outline.OutlinePage;
import org.jboss.ide.eclipse.freemarker.preferences.Preferences;
import org.jboss.ide.eclipse.freemarker.preferences.Preferences.PreferenceKey;
import org.jboss.ide.eclipse.freemarker.target.TargetLanguageSupport;
import org.jboss.ide.eclipse.freemarker.target.TargetLanguages;

import freemarker.core.ParseException;

/**
 * @author <a href="mailto:joe@binamics.com">Joe Hudson</a>
 */
public class Editor extends TextEditor implements KeyListener, MouseListener {

	private OutlinePage fOutlinePage;
	private org.jboss.ide.eclipse.freemarker.editor.Configuration configuration;

	private ItemSet itemSet;
	private Long itemSetModificationStamp;
	
	private boolean readOnly = false;

	private boolean mouseDown = false;

	public Editor() {
		super();
		configuration = new org.jboss.ide.eclipse.freemarker.editor.Configuration(
				getPreferenceStore(), this);
		setSourceViewerConfiguration(configuration);
		setDocumentProvider(Plugin.getDefault().getDocumentProvider());

	}

	@Override
	public void dispose() {
		ConfigurationManager.getInstance(getProject()).reload();
		super.dispose();
		if (matchingCharacterPainter != null) {
			matchingCharacterPainter.dispose();
		}
	}

	@Override
	public Object getAdapter(@SuppressWarnings("rawtypes") Class aClass) {
		Object adapter;
		if (aClass.equals(IContentOutlinePage.class)) {
			if (fOutlinePage == null) {
				fOutlinePage = new OutlinePage(this);
				if (getEditorInput() != null) {
					fOutlinePage.setInput(getEditorInput());
				}
			}
			adapter = fOutlinePage;
		} else {
			adapter = super.getAdapter(aClass);
		}
		return adapter;
	}

	protected static final char[] BRACKETS = { LexicalConstants.LEFT_BRACE,
			LexicalConstants.RIGHT_BRACE, LexicalConstants.LEFT_PARENTHESIS,
			LexicalConstants.RIGHT_PARENTHESIS,
			LexicalConstants.LEFT_SQUARE_BRACKET,
			LexicalConstants.RIGHT_SQUARE_BRACKET,
			LexicalConstants.LEFT_ANGLE_BRACKET,
			LexicalConstants.RIGHT_ANGLE_BRACKET };
	private MatchingCharacterPainter matchingCharacterPainter;

	@Override
	public void createPartControl(Composite parent) {
		super.createPartControl(parent);
		getSourceViewer().getTextWidget().addKeyListener(this);
		getSourceViewer().getTextWidget().addMouseListener(this);
		// matchingCharacterPainter = new MatchingCharacterPainter(
		// getSourceViewer(),
		// new JavaPairMatcher(BRACKETS));
		// ((SourceViewer)
		// getSourceViewer()).addPainter(matchingCharacterPainter);
	}

	@Override
	protected void createActions() {
		super.createActions();
		// Add content assist propsal action
		ContentAssistAction action = new ContentAssistAction(Plugin
				.getDefault().getResourceBundle(),
				"FreemarkerEditor.ContentAssist", this); //$NON-NLS-1$
		action.setActionDefinitionId(ITextEditorActionDefinitionIds.CONTENT_ASSIST_PROPOSALS);
		setAction("FreemarkerEditor.ContentAssist", action); //$NON-NLS-1$
		action.setEnabled(true);
	}

	@Override
	protected void handleCursorPositionChanged() {
		super.handleCursorPositionChanged();
		if (!mouseDown) {
			updateRelatedItemHighlights();
			
			int offset = getCaretOffset();
			Item item = getItemSet().getSelectedItem(offset);
			if (null == item && offset > 0)
				item = getItemSet().getSelectedItem(offset - 1);
			if (null == item) {
				item = getItemSet().getContextItem(getCaretOffset());
			}
			if (null != fOutlinePage) {
				fOutlinePage.update(item);
			}
		}
	}

	@Override
	public void mouseDoubleClick(MouseEvent e) {
		// Nothing to do
	}
	

	@Override
	public void keyPressed(KeyEvent e) {
		// Nothing to do
	}

	@Override
	public void keyReleased(KeyEvent e) {
		// Nothing to do
	}

	@Override
	public void mouseDown(MouseEvent e) {
		mouseDown = true;
	}

	@Override
	public void mouseUp(MouseEvent e) {
		mouseDown = false;
		handleCursorPositionChanged();
	}

	public void select(Item item) {
		selectAndReveal(item.getRegion().getOffset(), item.getRegion()
				.getLength());
	}

	public IDocument getDocument() {
		ISourceViewer viewer = getSourceViewer();
		if (viewer != null) {
			return viewer.getDocument();
		}
		return null;
	}

	public ITextViewer getTextViewer() {
		return getSourceViewer();
	}

	public void addProblemMarker(String aMessage, int aLine, int aCharStart, int aCharEnd) {
		IFile file = ((IFileEditorInput) getEditorInput()).getFile();
		try {
			Map<String, Object> attributes = new HashMap<String, Object>();
			MarkerUtilities.setMessage(attributes, aMessage);
			// There was no MarkerUtilities.setSeverity method:
			attributes.put(IMarker.SEVERITY, new Integer(IMarker.SEVERITY_ERROR));
			MarkerUtilities.setLineNumber(attributes, aLine);
            // Specify the column only if it isn't after the last char, otherwise Eclipse won't show the error marker
			// on the editor margin: 
			if (aCharStart < getDocument().getLength()) {			
    			MarkerUtilities.setCharStart(attributes, aCharStart);
    			MarkerUtilities.setCharEnd(attributes, aCharEnd);
			}
			MarkerUtilities.createMarker(file, attributes, IMarker.PROBLEM);
		} catch (CoreException e) {
			Plugin.log(e);
		}
	}

	/**
	 * Updates the related item highlights (background colorings) based on the current caret position.
	 */
	private void updateRelatedItemHighlights() {
		if (!Preferences.getInstance().getBoolean(PreferenceKey.HIGHLIGHT_RELATED_ITEMS)) {
			return;
		}
		
		// How it works:
		//
		// The positions of related items to show are calculated from the ItemSet and the caret position, but only
		// when the ItemSet is up to date with IDocument's state (see isItemSetUpToDate()). When it is, the calculated
		// positions are stored in the IDocument as Position-s with RELATED_ITEM_POSITION_CATEGORY category. When the
		// document is modified, these Position-s are updated synchronously; that's just an IDocument feature.
		// Meanwhile, the reconciler starts to create an updated ItemSet asynchronously. When it's done, the process
		// described here is restarted (see in reconcile(...)).
		//
		// Why do we need IDocument Position-s? Because to remove the old background colorings, we need to keep track
		// of the boundaries of them while the document is being modified, and so those boundaries change.  
		
		try {
			int caretOffset = getCaretOffset();
			IDocument doc = getDocument();
			Position[] docRelatedItemPositions = doc.getPositions(DocumentProvider.RELATED_ITEM_POSITION_CATEGORY);
			Position docRelatedItemPositionAtCaret = getPositionThatContainsOffset(caretOffset,
					docRelatedItemPositions);
			
			// If the caret is not at a place that belongs to the group of related items whose positions
			// the IDocument stores currently, we drop those positions.
			if (docRelatedItemPositionAtCaret == null) {
				for (Position docRelatedItemPosition : docRelatedItemPositions) {
					if (!docRelatedItemPosition.isDeleted() && docRelatedItemPosition.length != 0) {
						removeRelatedDirectiveTextStyle(docRelatedItemPosition);
					}
					doc.removePosition(DocumentProvider.RELATED_ITEM_POSITION_CATEGORY, docRelatedItemPosition);
				}
				docRelatedItemPositions = new RelatedItemPosition[0];
			}
			
			// Calculate newPositions, which stores the desired list of related directive positions and their
			// highlightedness.
			List<RelatedItemPosition> newPositions = new ArrayList<>();
			if (isItemSetUpToDate()) {
				Item selectedItem = getItemSet().getSelectedItem(caretOffset);
				if (selectedItem != null) {
					newPositions.add(new RelatedItemPosition(
							selectedItem.getRegion().getOffset(), selectedItem.getRegion().getLength(),
							false  /* not highlighted */));
					Item[] relatedItems = selectedItem.getRelatedItems();
					if (relatedItems != null) {
						for (Item relatedItem : relatedItems) {
							// Item.getRelatedItems() sometimes contains the item itself, sometimes it doesn't.
							// So we just emulate that it consistently never does.
							if (relatedItem != selectedItem) {
								newPositions.add(new RelatedItemPosition(
										relatedItem.getRegion().getOffset(), relatedItem.getRegion().getLength(),
										true /* highlighted */));
							}
						}
					}
				}
			} else if (docRelatedItemPositionAtCaret != null) {
				// The last ItemSet went out of sync with the IDocument content, so we can't use it. But as the caret is
				// at an item from a group of related items whose positions the IDocument currently tracks, we can still
				// update the highlightedness of those items.
				for (Position docRelatedItemPosition : docRelatedItemPositions) {
					boolean expectedHighlightedness = docRelatedItemPosition != docRelatedItemPositionAtCaret;
					if (expectedHighlightedness != ((RelatedItemPosition) docRelatedItemPosition).isHighlighted()) {
						newPositions.add(
								new RelatedItemPosition(
										docRelatedItemPosition.getOffset(), docRelatedItemPosition.getLength(),
										expectedHighlightedness));
					} else {
						newPositions.add((RelatedItemPosition) docRelatedItemPosition);
					}
				}
			}
			
			// Filter out items from newPositions that are the same as some we already have in the IDocument, also
			// remove and un-highlight old Positions that are not in newPositions:
			for (Position oldPosition : docRelatedItemPositions) {
				boolean oldPositionCanBeKept = false;
				for (Iterator<RelatedItemPosition> iterator = newPositions.iterator();
						!oldPositionCanBeKept && iterator.hasNext();) {
					RelatedItemPosition newPosition = iterator.next();
					
					if (newPosition.getOffset() == oldPosition.getOffset()
							&& newPosition.getLength() == oldPosition.getLength()
							&& newPosition.isHighlighted() == ((RelatedItemPosition) oldPosition).isHighlighted()) {
						iterator.remove(); // We won't add this newPosition.
						oldPositionCanBeKept = true;
					}
				}
				
				if (!oldPositionCanBeKept) {
					if (!oldPosition.isDeleted() && ((RelatedItemPosition) oldPosition).isHighlighted()) {
						removeRelatedDirectiveTextStyle(oldPosition);
					}
					doc.removePosition(DocumentProvider.RELATED_ITEM_POSITION_CATEGORY, oldPosition);
				}
			}
			
			// Add positions and highlights for the remaining (i.e., truly new) newPositions: 
			for (RelatedItemPosition newPosition : newPositions) {
				if (newPosition.isHighlighted()) {
					addRelatedDirectiveTextStyle(newPosition);
				}
				doc.addPosition(DocumentProvider.RELATED_ITEM_POSITION_CATEGORY, newPosition);
			}
		} catch (BadPositionCategoryException | BadLocationException e) {
			// Should never occur
			throw new IllegalStateException(e);
		}
	}

	private boolean isItemSetUpToDate() {
		long modificationStamp = ((IDocumentExtension4) getDocument()).getModificationStamp();
		return itemSetModificationStamp != null && modificationStamp == itemSetModificationStamp;
	}

	private Position getPositionThatContainsOffset(int offset, Position[] positions) {
		if (positions == null) {
			return null;
		}
		for (Position position : positions) {
			if (position.includes(offset)) {
				return position;
			}
		}
		return null;
	}

	/**
	 * Paints a region of the text as "related item".
	 */
	private void addRelatedDirectiveTextStyle(Position position) {
		getSourceViewer().getTextWidget().setStyleRange(
				new StyleRange(
						position.getOffset(), position.getLength(),
						null, // General / Editors / Text Editors / Foreground color
						Preferences.getInstance().getColor(PreferenceKey.COLOR_RELATED_ITEM)));
	}

	/**
	 * Removes the effect of {@link #addRelatedDirectiveTextStyle(int, int)} inside the specified {@link Position}.
	 */
	private void removeRelatedDirectiveTextStyle(Position position) {
		if (getSourceViewer() instanceof ITextViewerExtension2) {
			((ITextViewerExtension2) getSourceViewer())
					.invalidateTextPresentation(position.getOffset(), position.getLength());
		} else {
			getSourceViewer().invalidateTextPresentation();
		}
	}
	
	public Item getSelectedItem(boolean allowFudge) {
		int caretOffset = getCaretOffset();
		Item item = getItemSet().getSelectedItem(getCaretOffset());
		if (null == item && caretOffset > 0)
			item = getItemSet().getSelectedItem(caretOffset - 1);
		return item;
	}

	public Item getSelectedItem() {
		return getItemSet().getSelectedItem(getCaretOffset());
	}

	public int getCaretOffset() {
		return getSourceViewer().getTextWidget().getCaretOffset();
	}

	public ItemSet getItemSet() {
		if (null == this.itemSet) {
			this.itemSet = createItemSet(Collections.<ITypedRegion> emptyList());
		}
		return this.itemSet;
	}

	/**
	 * Creates a new {@link ItemSet} based on the given {@link List} or regions.
	 * This method is public for the sake of testing.
	 *
	 * @param regions
	 * @return
	 */
	public ItemSet createItemSet(List<ITypedRegion> regions) {
		IResource resource = null;
		IEditorInput input = getEditorInput();
		if (input instanceof IFileEditorInput) {
			resource = ((IFileEditorInput) input).getFile();
			// } else if (getEditorInput() instanceof JarEntryEditorInput) {
			// resource = null;
		}
		return new ItemSet(getSourceViewer(), regions, resource);
	}

	public OutlinePage getOutlinePage() {
		return fOutlinePage;
	}

	public IProject getProject() {
		return ((IFileEditorInput) getEditorInput()).getFile().getProject();
	}

	public IFile getFile() {
		return (null != getEditorInput()) ? ((IFileEditorInput) getEditorInput())
				.getFile() : null;
	}

	private TargetLanguageSupport targetLanguageSupport;

	@Override
	public boolean isEditorInputReadOnly() {
		return readOnly;
	}

	public void setReadOnly(boolean readOnly) {
		this.readOnly = readOnly;
	}

	/**
	 * Reconciles in the current thread using a newly created
	 * {@link ReconcilingStrategy}.
	 * <p>
	 * For test purposes. Useful when testing functionality related to
	 * {@link #getItemSet()} The {@link ReconcilingStrategy} configured inside
	 * presentation reconciler works asynchronously and therefore
	 * {@link #getItemSet()} returns an empty {@link ItemSet} when called from
	 * the UI thread just after opening the editor. Calling this method before
	 * {@link #getItemSet()} fixes the problem.
	 * <p>
	 * This method can be called from the UI thread.
	 *
	 */
	public void reconcileInstantly() {
		ReconcilingStrategy reconciler = new ReconcilingStrategy(this);
		reconciler.setDocument(getDocument());
		reconciler.reconcile();
	}

	/**
	 * Normally called from the reconciler thread to notify the UI thread about
	 * a new region list become available.
	 * 
	 * @param modificationStamp
	 *            The {@link IDocumentExtension4#getModificationStamp()} for
	 *            which the regions were created, or {@code null} if the stamp
	 *            was changed while the ranges were created, and so it was made from an
	 *            inconsistent editor content.
	 */
	void updateModel(List<ITypedRegion> regions, final Long modificationStamp) {
		/* re-create the model in the reconciler thread */
		final ItemSet newItemSet = createItemSet(regions);
		executeInUIThread(
			new Runnable() {
				@Override
				public void run() {
					itemSet = newItemSet;
					itemSetModificationStamp = modificationStamp;
					
					updateRelatedItemHighlights();
					
					if (null != Editor.this.fOutlinePage) {
						Editor.this.fOutlinePage.refresh();
					}
				}
			}
		);
	}

	/**
	 * Normally called from the reconciler thread to notify the UI thread about
	 * a new region list become available.
	 */
	void updateMarkers(final ParseException pe) {
		executeInUIThread(new Runnable() {
			@Override
			public void run() {
				try {
					getFile().deleteMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
					if (pe != null) {
						addProblemMarker(pe.getEditorMessage(), pe.getLineNumber(),
								getDocument().getLineOffset(pe.getLineNumber() - 1) + pe.getColumnNumber() - 1,
								getDocument().getLineOffset(pe.getEndLineNumber() - 1) + pe.getEndColumnNumber());
					}
				} catch (Exception e) {
					Plugin.log(e);
				}
			}
		});
	}
	
	private void executeInUIThread(Runnable task) {
		/* make sure to run in the UI thread */
		if (Thread.currentThread() == Display.getDefault().getThread()) {
			/* we are in the UI thread - run synchrounously */
			task.run();
		} else {
			/* run asynchronously */
			Display.getDefault().asyncExec(task);
		}
	}

	public TargetLanguageSupport getTargetLanguageSupport() {
		if (targetLanguageSupport == null) {
			targetLanguageSupport = TargetLanguages.findSupport(getEditorInput());
		}
		return targetLanguageSupport;
	}
	
}