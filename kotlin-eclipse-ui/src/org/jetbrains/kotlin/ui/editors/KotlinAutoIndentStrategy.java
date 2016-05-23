/*******************************************************************************
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package org.jetbrains.kotlin.ui.editors;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.internal.ui.javaeditor.JavaEditor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentCommand;
import org.eclipse.jface.text.IAutoEditStrategy;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.TextUtilities;
import org.jetbrains.kotlin.core.builder.KotlinPsiManager;
import org.jetbrains.kotlin.core.log.KotlinLogger;
import org.jetbrains.kotlin.eclipse.ui.utils.EditorUtil;
import org.jetbrains.kotlin.eclipse.ui.utils.IndenterUtil;
import org.jetbrains.kotlin.eclipse.ui.utils.LineEndUtil;
import org.jetbrains.kotlin.idea.formatter.KotlinSpacingRulesKt;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.ui.formatter.KotlinBlock;
import org.jetbrains.kotlin.ui.formatter.KotlinFormatModelKt;
import org.jetbrains.kotlin.ui.formatter.KotlinFormatterKt;

import com.intellij.formatting.FormatUtilsKt;
import com.intellij.formatting.FormatterFactory;
import com.intellij.formatting.FormatterImpl;
import com.intellij.formatting.Indent;
import com.intellij.formatting.IndentInEditor;
import com.intellij.formatting.IndentInEditor.BlockIndent;
import com.intellij.formatting.KotlinSpacingBuilderUtilImpl;
import com.intellij.psi.codeStyle.CodeStyleSettings;

public class KotlinAutoIndentStrategy implements IAutoEditStrategy {
    
    private static final char OPENING_BRACE_CHAR = '{';
    private static final char CLOSING_BRACE_CHAR = '}';
    private static final String CLOSING_BRACE_STRING = Character.toString(CLOSING_BRACE_CHAR);
    
    private final JavaEditor editor;
    
    public KotlinAutoIndentStrategy(JavaEditor editor) {
        this.editor = editor;
        new FormatterFactory();
    }
    
    @Override
    public void customizeDocumentCommand(IDocument document, DocumentCommand command) {
        if (command.doit == false) {
            return;
        }
        
        if (command.length == 0 && command.text != null && isNewLine(document, command.text)) {
            autoEditAfterNewLine(document, command);
        } else if (CLOSING_BRACE_STRING.equals(command.text)) {
            autoEditBeforeCloseBrace(document, command);
        }
    }
    
    private IndentInEditor computeIndent(IDocument document, int offset) {
        new FormatterImpl();
        IFile file = EditorUtil.getFile(editor);
        if (file == null) {
            KotlinLogger.logError("Failed to retrieve IFile from editor " + editor, null);
            return BlockIndent.NO_INDENT;
        }
        
        KtFile ktFile = KotlinPsiManager.getKotlinFileIfExist(file, document.get());
        if (ktFile == null) {
            return BlockIndent.NO_INDENT;
        }
        
        IJavaProject javaProject = ((KotlinFileEditor) editor).getJavaProject();
        if (javaProject == null) return BlockIndent.NO_INDENT;
        int resolvedOffset = LineEndUtil.convertCrToDocumentOffset(document, offset);
        
        CodeStyleSettings settings = KotlinFormatterKt.getSettings();
        KotlinBlock rootBlock = new KotlinBlock(ktFile.getNode(), 
                KotlinFormatterKt.getNULL_ALIGNMENT_STRATEGY(), 
                Indent.getNoneIndent(), 
                null,
                settings,
                KotlinSpacingRulesKt.createSpacingBuilder(settings, KotlinSpacingBuilderUtilImpl.INSTANCE));
        
        KotlinFormatModelKt.tryAdjustIndent(ktFile, rootBlock, settings, resolvedOffset);
        
        return FormatUtilsKt.computeAlignment(ktFile, resolvedOffset, rootBlock);
    }
    
    private void autoEditAfterNewLine(IDocument document, DocumentCommand command) {
        if (command.offset == -1 || document.getLength() == 0) {
            return;
        }
        
        try {
            int p = command.offset == document.getLength() ? command.offset - 1 : command.offset;
            IRegion info = document.getLineInformationOfOffset(p);
            
            int start = info.getOffset();
            
            IndentInEditor indent = computeIndent(document, command.offset);
            
            boolean afterOpenBrace = isAfterOpenBrace(document, command.offset - 1, start);
            boolean beforeCloseBrace = isBeforeCloseBrace(document, command.offset, info.getOffset() + info.getLength()) && indent instanceof BlockIndent;
            if (beforeCloseBrace && !afterOpenBrace) {
                BlockIndent blockIndent = (BlockIndent) indent;
                indent = new BlockIndent(blockIndent.getIndent() - 1);
            }
            int oldOffset = command.offset;
            int newOffset = findEndOfWhiteSpace(document, command.offset - 1) + 1;
            if (newOffset > 0 && !IndenterUtil.isWhiteSpaceOrNewLine(document.getChar(newOffset - 1))) {
                command.offset = newOffset;
                command.text = IndenterUtil.createWhiteSpace(indent, 1, TextUtilities.getDefaultLineDelimiter(document));
                command.length = oldOffset - newOffset;
            } else {
                command.text += IndenterUtil.createWhiteSpace(indent, 0, TextUtilities.getDefaultLineDelimiter(document));
            }
            
            if (beforeCloseBrace && afterOpenBrace) {
                BlockIndent blockIndent = (BlockIndent) indent;
                String shift = IndenterUtil.createWhiteSpace(blockIndent.getIndent() - 1, 1, TextUtilities.getDefaultLineDelimiter(document));
                command.caretOffset = command.offset + command.text.length();
                command.text += shift;
                command.shiftsCaret = false;
                command.length += document.get().indexOf(CLOSING_BRACE_CHAR, p) - p;
            }
        } catch (BadLocationException e) {
            KotlinLogger.logAndThrow(e);
        }
    }
    
    private void autoEditBeforeCloseBrace(IDocument document, DocumentCommand command) {
        if (isNewLineBefore(document, command.offset)) {
            try {
                int spaceLength = command.offset - findEndOfWhiteSpaceBefore(document, command.offset - 1, 0) - 1;
                IndentInEditor indent = computeIndent(document, command.offset);
                if (indent instanceof BlockIndent) {
                    BlockIndent blockIndent = (BlockIndent) indent;
                    indent = new BlockIndent(blockIndent.getIndent() - 1);
                }
                command.text = IndenterUtil.createWhiteSpace(indent, 0, 
                        TextUtilities.getDefaultLineDelimiter(document)) + CLOSING_BRACE_STRING;
                command.offset -= spaceLength;
                document.replace(command.offset, spaceLength, "");
            } catch (BadLocationException e) {
                KotlinLogger.logAndThrow(e);
            }
        }
    }
    
    private static int findEndOfWhiteSpaceAfter(IDocument document, int offset, int end) throws BadLocationException {
        while (offset < end) {
            if (!IndenterUtil.isWhiteSpaceChar(document.getChar(offset))) {
                return offset;
            }
            
            offset++;
        }
        
        return end;
    }
    
    private static int findEndOfWhiteSpaceBefore(IDocument document, int offset, int start) throws BadLocationException {
        while (offset >= start) {
            if (!IndenterUtil.isWhiteSpaceChar(document.getChar(offset))) {
                return offset;
            }
            
            offset--;
        }
        
        return start;
    }
    
    private static boolean isAfterOpenBrace(IDocument document, int offset, int startLineOffset) throws BadLocationException {
        int nonEmptyOffset = findEndOfWhiteSpaceBefore(document, offset, startLineOffset);
        return document.getChar(nonEmptyOffset) == OPENING_BRACE_CHAR;
    }
    
    private static boolean isBeforeCloseBrace(IDocument document, int offset, int endLineOffset) throws BadLocationException {
        int nonEmptyOffset = findEndOfWhiteSpaceAfter(document, offset, endLineOffset);
        if (nonEmptyOffset == document.getLength()) {
            nonEmptyOffset--;
        }
        return document.getChar(nonEmptyOffset) == CLOSING_BRACE_CHAR;
    }
    
    private static int findEndOfWhiteSpace(IDocument document, int offset) throws BadLocationException {
        while (offset > 0) {
            char c = document.getChar(offset);
            if (!IndenterUtil.isWhiteSpaceChar(c)) {
                return offset;
            }
            
            offset--;
        }
        
        return offset;
    }
    
    private static boolean isNewLineBefore(IDocument document, int offset) {
        try {
            offset--;
            char prev = IndenterUtil.SPACE_CHAR;
            StringBuilder bufBefore = new StringBuilder(prev);
            while (IndenterUtil.isWhiteSpaceChar(prev) && offset > 0) {
                prev = document.getChar(offset--);
                bufBefore.append(prev);
            }
            
            return containsNewLine(document, bufBefore.toString());
        } catch (BadLocationException e) {
            KotlinLogger.logAndThrow(e);
        }
        
        return false;
    }
    
    private static boolean containsNewLine(IDocument document, String text) {
        String[] delimiters = document.getLegalLineDelimiters();
        for (String delimiter : delimiters) {
            if (text.contains(delimiter)) {
                return true;
            }
        }
        
        return false;
    }
    
    private static boolean isNewLine(IDocument document, String text) {
        String[] delimiters = document.getLegalLineDelimiters();
        for (String delimiter : delimiters) {
            if (delimiter.equals(text)) {
                return true;
            }
        }
        
        return false;
    }
}