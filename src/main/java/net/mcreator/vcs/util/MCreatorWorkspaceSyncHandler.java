/*
 * MCreator VCS plugin
 * Copyright (C) 2023, Defeatomizer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.mcreator.vcs.util;

import net.mcreator.Launcher;
import net.mcreator.element.GeneratableElement;
import net.mcreator.generator.Generator;
import net.mcreator.generator.GeneratorTemplate;
import net.mcreator.io.FileIO;
import net.mcreator.ui.MCreator;
import net.mcreator.util.MCreatorVersionNumber;
import net.mcreator.util.diff.DiffResult;
import net.mcreator.util.diff.GSONCompare;
import net.mcreator.util.diff.ListDiff;
import net.mcreator.util.diff.MapDiff;
import net.mcreator.vcs.ui.dialogs.VCSFileMergeDialog;
import net.mcreator.vcs.ui.dialogs.VCSWorkspaceMergeDialog;
import net.mcreator.vcs.util.diff.DiffResultToBaseConflictFinder;
import net.mcreator.vcs.util.diff.MergeHandle;
import net.mcreator.vcs.util.diff.ResultSide;
import net.mcreator.workspace.TerribleWorkspaceHacks;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.WorkspaceFileManager;
import net.mcreator.workspace.elements.FolderElement;
import net.mcreator.workspace.elements.ModElement;
import net.mcreator.workspace.elements.SoundElement;
import net.mcreator.workspace.elements.VariableElement;
import net.mcreator.workspace.settings.WorkspaceSettings;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class MCreatorWorkspaceSyncHandler implements ICustomSyncHandler {

	private final MCreator mcreator;

	public MCreatorWorkspaceSyncHandler(MCreator mcreator) {
		this.mcreator = mcreator;
	}

	@Override
	public boolean handleSync(Git git, boolean hasMergeConflicts, List<FileSyncHandle> handles, boolean dryRun)
			throws GitAPIException, IOException {
		boolean required_user_action;

		List<FileSyncHandle> unprocessedHandles = new ArrayList<>(handles);

		Workspace localWorkspace = mcreator.getWorkspace();
		Workspace remoteWorkspace = null;
		Workspace baseWorkspace = null;

		boolean conflictsInWorkspaceFile = false;

		// First we check if the remote has any changes on the workspace file
		for (FileSyncHandle handle : handles) {
			if (handle.getBasePath().equals(localWorkspace.getFileManager().getWorkspaceFile().getName())) {
				conflictsInWorkspaceFile =
						handle.isUnmerged() && handle.getChangeTypeRelativeToRemote() != DiffEntry.ChangeType.DELETE;
				if (conflictsInWorkspaceFile)
					baseWorkspace = getVirtualWorkspace(localWorkspace, new String(handle.getBaseBytes()));
				if (handle.getChangeTypeRelativeToRemote() != DiffEntry.ChangeType.DELETE)
					remoteWorkspace = getVirtualWorkspace(localWorkspace, new String(handle.getRemoteBytes()));
				else
					remoteWorkspace = baseWorkspace;
				unprocessedHandles.remove(handle);
				break;
			}
		}

		// remote workspace could be newer than the latest workspace version supported by this MCreator
		if (remoteWorkspace != null && remoteWorkspace != baseWorkspace)
			if (remoteWorkspace.getMCreatorVersion() > Launcher.version.versionlong
					&& !MCreatorVersionNumber.isBuildNumberDevelopment(remoteWorkspace.getMCreatorVersion()))
				throw new IOException("Too new workspace version: " + remoteWorkspace.getMCreatorVersion());

		Set<MergeHandle<ModElement>> conflictingModElements = new HashSet<>();
		Map<ModElement, List<FileSyncHandle>> conflictingFilesOfModElementMap = new HashMap<>();

		// check for mod element changes
		for (FileSyncHandle handle : handles) {
			if (!handle.isUnmerged()) // if this file is not conflicting/unmerged, we skip it
				continue;

			File file = handle.toFileInWorkspace(localWorkspace, ResultSide.BASE);

			// check if this file is one of the lang files, we skip it as lang files are auto-regenerated
			if (file.getCanonicalPath()
					.startsWith(localWorkspace.getGenerator().getLangFilesRoot().getCanonicalPath())) {
				unprocessedHandles.remove(handle);
				continue;
			}

			// test if this file belongs to mod element definitions folder
			if (file.getCanonicalPath()
					.startsWith(localWorkspace.getFolderManager().getModElementsDir().getCanonicalPath())) {
				String modElement = file.getName().replace(".mod.json", "");
				ModElement testModElementDefinition = localWorkspace.getModElementByName(modElement);
				if (testModElementDefinition != null) {
					conflictingModElements.add(new MergeHandle<>(testModElementDefinition, testModElementDefinition,
							handle.getChangeTypeRelativeToLocal(), handle.getChangeTypeRelativeToRemote()));
					// add conflicting file of mod element to the list
					conflictingFilesOfModElementMap.putIfAbsent(testModElementDefinition,
							new ArrayList<>()); // init list if not already
					conflictingFilesOfModElementMap.get(testModElementDefinition).add(handle);
					unprocessedHandles.remove(handle);
					continue;
				}
			}

			// test if this file belongs to generated code of mod element
			ModElement testGeneratedElements = localWorkspace.getGenerator().getModElementThisFileBelongsTo(file);
			if (testGeneratedElements != null) {
				conflictingModElements.add(new MergeHandle<>(testGeneratedElements, testGeneratedElements,
						handle.getChangeTypeRelativeToLocal(), handle.getChangeTypeRelativeToRemote()));
				// add conflicting file of mod element to the list
				conflictingFilesOfModElementMap.putIfAbsent(testGeneratedElements,
						new ArrayList<>()); // init list if not already
				conflictingFilesOfModElementMap.get(testGeneratedElements).add(handle);
				unprocessedHandles.remove(handle);
			}
		}

		MergeHandle<WorkspaceSettings> workspaceSettingsMergeHandle = null;
		Set<MergeHandle<VariableElement>> conflictingVariableElements = new HashSet<>();
		Set<MergeHandle<SoundElement>> conflictingSoundElements = new HashSet<>();
		Set<MergeHandle<String>> conflictingLangMaps = new HashSet<>();
		MergeHandle<FolderElement> workspaceFoldersMergeHandle = null; // for cases where we can't do automatic merge

		if (conflictsInWorkspaceFile) {
			// WORKSPACE SETTINGS
			boolean settingsChangedRemoteToBase = !GSONCompare.deepEquals(baseWorkspace.getWorkspaceSettings(),
					remoteWorkspace.getWorkspaceSettings());
			boolean settingsChangedLocalToBase = !GSONCompare.deepEquals(baseWorkspace.getWorkspaceSettings(),
					localWorkspace.getWorkspaceSettings());

			// settings changed local to base and remote to base, we have conflict
			if (settingsChangedRemoteToBase && settingsChangedLocalToBase) {
				workspaceSettingsMergeHandle = new MergeHandle<>(localWorkspace.getWorkspaceSettings(),
						remoteWorkspace.getWorkspaceSettings(), DiffEntry.ChangeType.MODIFY,
						DiffEntry.ChangeType.MODIFY);
			}

			// MOD ELEMENTS
			DiffResult<ModElement> modElementListDiffLocalToBase = ListDiff.getListDiff(baseWorkspace.getModElements(),
					localWorkspace.getModElements());
			DiffResult<ModElement> modElementListDiffRemoteToBase = ListDiff.getListDiff(baseWorkspace.getModElements(),
					remoteWorkspace.getModElements());

			conflictingModElements.addAll(DiffResultToBaseConflictFinder.findConflicts(modElementListDiffLocalToBase,
					modElementListDiffRemoteToBase)); // add all that were affected on both diffs to conflicting list

			if (!dryRun) {
				localWorkspace.getModElementManager().invalidateCache();

				// first we remove local to base, skipping conflicted elements
				for (ModElement removedElement : modElementListDiffLocalToBase.removed()) {
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingModElements, removedElement))
						baseWorkspace.removeModElement(removedElement);
				}

				// then we remove remote to base, skipping conflicted elements
				for (ModElement removedElement : modElementListDiffRemoteToBase.removed()) {
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingModElements, removedElement))
						baseWorkspace.removeModElement(removedElement);
				}

				// then we add local to base, skipping conflicted elements
				for (ModElement addedElement : modElementListDiffLocalToBase.added()) {
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingModElements, addedElement)) {
						baseWorkspace.addModElement(addedElement);
						GeneratableElement generatableElement = addedElement.getGeneratableElement();
						if (generatableElement != null) {
							baseWorkspace.getGenerator().generateElement(
									generatableElement); // regenerate this mod element to reduce conflicts number
						}
					}
				}

				// then we add remote to base, skipping conflicted elements
				for (ModElement addedElement : modElementListDiffRemoteToBase.added()) {
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingModElements, addedElement)) {
						baseWorkspace.addModElement(addedElement);
						GeneratableElement generatableElement = addedElement.getGeneratableElement();
						if (generatableElement != null) {
							baseWorkspace.getGenerator().generateElement(
									generatableElement); // regenerate this mod element to reduce conflicts number
							localWorkspace.getModElementManager().storeModElementPicture(
									generatableElement); // we regenerate mod element images as we do not have remote images yet
						}
					}
				}
			}

			// WORKSPACE FOLDERS
			if (!FolderSyncHandler.mergeFoldersRecursively(localWorkspace.getFoldersRoot(),
					remoteWorkspace.getFoldersRoot(), baseWorkspace.getFoldersRoot(), dryRun)) {
				// mergeFoldersRecursively returned false -> failed to auto-merge, prepare merge handle
				workspaceFoldersMergeHandle = new MergeHandle<>(localWorkspace.getFoldersRoot(),
						remoteWorkspace.getFoldersRoot(), DiffEntry.ChangeType.MODIFY, DiffEntry.ChangeType.MODIFY);
			}

			// VARIABLE ELEMENTS (same concept as for mod elements)
			DiffResult<VariableElement> variableElementListDiffLocalToBase = ListDiff.getListDiff(
					baseWorkspace.getVariableElements(), localWorkspace.getVariableElements());
			DiffResult<VariableElement> variableElementListDiffRemoteToBase = ListDiff.getListDiff(
					baseWorkspace.getVariableElements(), remoteWorkspace.getVariableElements());

			conflictingVariableElements.addAll(
					DiffResultToBaseConflictFinder.findConflicts(variableElementListDiffLocalToBase,
							variableElementListDiffRemoteToBase));

			if (!dryRun) {
				for (VariableElement removedVariableElement : variableElementListDiffLocalToBase.removed())
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingVariableElements,
							removedVariableElement))
						baseWorkspace.removeVariableElement(removedVariableElement);

				for (VariableElement removedVariableElement : variableElementListDiffRemoteToBase.removed())
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingVariableElements,
							removedVariableElement))
						baseWorkspace.removeVariableElement(removedVariableElement);

				for (VariableElement addedVariableElement : variableElementListDiffLocalToBase.added())
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingVariableElements,
							addedVariableElement))
						baseWorkspace.addVariableElement(addedVariableElement);

				for (VariableElement addedVariableElement : variableElementListDiffRemoteToBase.added())
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingVariableElements,
							addedVariableElement))
						baseWorkspace.addVariableElement(addedVariableElement);
			}

			// SOUND ELEMENTS (same concept as for mod elements)
			DiffResult<SoundElement> soundElementListDiffLocalToBase = ListDiff.getListDiff(
					baseWorkspace.getSoundElements(), localWorkspace.getSoundElements());
			DiffResult<SoundElement> soundElementListDiffRemoteToBase = ListDiff.getListDiff(
					baseWorkspace.getSoundElements(), remoteWorkspace.getSoundElements());

			conflictingSoundElements.addAll(
					DiffResultToBaseConflictFinder.findConflicts(soundElementListDiffLocalToBase,
							soundElementListDiffRemoteToBase));

			if (!dryRun) {
				for (SoundElement removedSoundElement : soundElementListDiffLocalToBase.removed())
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingSoundElements, removedSoundElement))
						baseWorkspace.removeSoundElement(removedSoundElement);

				for (SoundElement removedSoundElement : soundElementListDiffRemoteToBase.removed())
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingSoundElements, removedSoundElement))
						baseWorkspace.removeSoundElement(removedSoundElement);

				for (SoundElement addedSoundElement : soundElementListDiffLocalToBase.added())
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingSoundElements, addedSoundElement))
						baseWorkspace.addSoundElement(addedSoundElement);

				for (SoundElement addedSoundElement : soundElementListDiffRemoteToBase.added())
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingSoundElements, addedSoundElement))
						baseWorkspace.addSoundElement(addedSoundElement);
			}

			// LANGUAGE MAP
			Map<String, LinkedHashMap<String, String>> base_language_map = baseWorkspace.getLanguageMap();
			Map<String, LinkedHashMap<String, String>> local_language_map = localWorkspace.getLanguageMap();
			Map<String, LinkedHashMap<String, String>> remote_language_map = remoteWorkspace.getLanguageMap();

			DiffResult<String> langMapDiffLocalToBase = MapDiff.getMapDiff(base_language_map, local_language_map);
			DiffResult<String> langMapDiffRemoteToBase = MapDiff.getMapDiff(base_language_map, remote_language_map);

			conflictingLangMaps.addAll(
					DiffResultToBaseConflictFinder.findConflicts(langMapDiffLocalToBase, langMapDiffRemoteToBase));

			if (!dryRun) {
				for (String removedLangMap : langMapDiffLocalToBase.removed())
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingLangMaps, removedLangMap))
						baseWorkspace.getLanguageMap().remove(removedLangMap);

				for (String removedLangMap : langMapDiffRemoteToBase.removed())
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingLangMaps, removedLangMap))
						baseWorkspace.getLanguageMap().remove(removedLangMap);

				for (String addedLangMap : langMapDiffLocalToBase.added())
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingLangMaps, addedLangMap))
						if (localWorkspace.getLanguageMap().get(addedLangMap) != null)
							baseWorkspace.getLanguageMap()
									.put(addedLangMap, localWorkspace.getLanguageMap().get(addedLangMap));

				for (String addedLangMap : langMapDiffRemoteToBase.added())
					if (MergeHandle.isElementNotInMergeHandleCollection(conflictingLangMaps, addedLangMap))
						if (remoteWorkspace.getLanguageMap().get(addedLangMap) != null)
							baseWorkspace.getLanguageMap()
									.put(addedLangMap, remoteWorkspace.getLanguageMap().get(addedLangMap));
			}

			Set<MergeHandle<String>> conflictingLangMapsTmp = new HashSet<>(conflictingLangMaps);
			for (MergeHandle<String> langMergeHandle : conflictingLangMapsTmp) {
				// we can only merge automatically modify type changes, we can't merge
				if (langMergeHandle.getLocalChange() == DiffEntry.ChangeType.MODIFY
						&& langMergeHandle.getRemoteChange() == DiffEntry.ChangeType.MODIFY) {
					String language = langMergeHandle.getLocal();

					LinkedHashMap<String, String> base_translation = base_language_map.get(language);
					LinkedHashMap<String, String> local_translation = local_language_map.get(language);
					LinkedHashMap<String, String> remote_translation = remote_language_map.get(language);

					DiffResult<String> langMapContentsDiffLocalToBase = MapDiff.getMapDiff(base_translation,
							local_translation);
					DiffResult<String> langMapContentsDiffRemoteToBase = MapDiff.getMapDiff(base_translation,
							remote_translation);

					Set<MergeHandle<String>> conflictingLangEntries = DiffResultToBaseConflictFinder.findConflicts(
							langMapContentsDiffLocalToBase, langMapContentsDiffRemoteToBase);

					for (String removedLangEntry : langMapContentsDiffLocalToBase.removed())
						if (MergeHandle.isElementNotInMergeHandleCollection(conflictingLangEntries, removedLangEntry))
							baseWorkspace.removeLocalizationEntryByKey(removedLangEntry);

					for (String removedLangEntry : langMapContentsDiffRemoteToBase.removed())
						if (MergeHandle.isElementNotInMergeHandleCollection(conflictingLangEntries, removedLangEntry))
							baseWorkspace.removeLocalizationEntryByKey(removedLangEntry);

					for (String addedLangEntry : langMapContentsDiffLocalToBase.added())
						if (MergeHandle.isElementNotInMergeHandleCollection(conflictingLangEntries, addedLangEntry))
							baseWorkspace.getLanguageMap().get(language).put(addedLangEntry,
									localWorkspace.getLanguageMap().get(language).get(addedLangEntry));

					for (String addedLangEntry : langMapContentsDiffRemoteToBase.added())
						if (MergeHandle.isElementNotInMergeHandleCollection(conflictingLangEntries, addedLangEntry))
							baseWorkspace.getLanguageMap().get(language).put(addedLangEntry,
									remoteWorkspace.getLanguageMap().get(language).get(addedLangEntry));

					// if it can merge silently (no conflicts), we remove this merge handle from conflicting language maps
					// as we will do auto merge
					if (conflictingLangEntries.isEmpty())
						conflictingLangMaps.remove(langMergeHandle);
				}
			}
		}

		// next we can decide if required_user_action will be needed
		boolean workspace_manual_merge_required =
				workspaceSettingsMergeHandle != null || !conflictingModElements.isEmpty()
						|| !conflictingSoundElements.isEmpty() || !conflictingVariableElements.isEmpty()
						|| !conflictingLangMaps.isEmpty() || workspaceFoldersMergeHandle != null;

		required_user_action = workspace_manual_merge_required;

		if (!dryRun && workspace_manual_merge_required) {
			// Show workspace merge dialog
			VCSWorkspaceMergeDialog.show(mcreator,
					new WorkspaceMergeHandles(workspaceSettingsMergeHandle, conflictingModElements,
							conflictingVariableElements, conflictingSoundElements, conflictingLangMaps,
							workspaceFoldersMergeHandle));

			// after UI merge is complete, we apply the merge to the workspace

			if (conflictsInWorkspaceFile && workspaceSettingsMergeHandle != null)
				baseWorkspace.setWorkspaceSettings(workspaceSettingsMergeHandle.getSelectedResult());

			if (conflictsInWorkspaceFile && workspaceFoldersMergeHandle != null) {
				baseWorkspace.getFoldersRoot().getDirectFolderChildren()
						.forEach(baseWorkspace.getFoldersRoot()::removeChild);
				workspaceFoldersMergeHandle.getSelectedResult().getDirectFolderChildren()
						.forEach(baseWorkspace.getFoldersRoot()::addChild);
			}

			for (MergeHandle<ModElement> modElementMergeHandle : conflictingModElements) {
				if (conflictsInWorkspaceFile) {
					if (modElementMergeHandle.getSelectedResultChangeType() == DiffEntry.ChangeType.ADD) {
						baseWorkspace.addModElement(modElementMergeHandle.getSelectedResult());
					} else if (modElementMergeHandle.getSelectedResultChangeType() == DiffEntry.ChangeType.DELETE) {
						baseWorkspace.removeModElement(modElementMergeHandle.getSelectedResult());
					} else if (modElementMergeHandle.getSelectedResultChangeType() == DiffEntry.ChangeType.MODIFY) {
						ModElement element = modElementMergeHandle.getSelectedResult();
						for (ModElement el : baseWorkspace.getModElements()) {
							if (el == element || el.getName().equals(element.getName())) {
								el.loadDataFrom(element);

								// update ME and MCItem icons
								el.reloadElementIcon();
								el.getMCItems().forEach(mcItem -> mcItem.icon.getImage().flush());

								break; // there can be only one element with given name so no need to iterate further
							}
						}
						baseWorkspace.markDirty();
					}
				}

				List<FileSyncHandle> modElementFiles = conflictingFilesOfModElementMap.get(
						modElementMergeHandle.getSelectedResult());

				if (modElementFiles != null) {
					for (FileSyncHandle fileSyncHandle : modElementFiles) {
						mergeNormalFile(localWorkspace, fileSyncHandle, modElementMergeHandle);
					}
				}

				// at last, we regenerate these mod elements too
				GeneratableElement generatableElement = modElementMergeHandle.getSelectedResult()
						.getGeneratableElement();
				if (generatableElement != null) {
					// regenerate this mod element to reduce conflicts number, we prefer to use baseWorkspace for this
					Objects.requireNonNullElse(baseWorkspace, localWorkspace).getGenerator()
							.generateElement(generatableElement);
					localWorkspace.getModElementManager().storeModElementPicture(
							generatableElement); // we regenerate mod element images as we do not have remote images yet
				}
			}

			if (conflictsInWorkspaceFile) {
				for (MergeHandle<VariableElement> variableElementMergeHandle : conflictingVariableElements) {
					if (variableElementMergeHandle.getSelectedResultChangeType() == DiffEntry.ChangeType.ADD) {
						baseWorkspace.addVariableElement(variableElementMergeHandle.getSelectedResult());
					} else if (variableElementMergeHandle.getSelectedResultChangeType()
							== DiffEntry.ChangeType.DELETE) {
						baseWorkspace.removeVariableElement(variableElementMergeHandle.getSelectedResult());
					} else if (variableElementMergeHandle.getSelectedResultChangeType()
							== DiffEntry.ChangeType.MODIFY) {
						for (VariableElement el : baseWorkspace.getVariableElements()) {
							if (el.getName().equals(variableElementMergeHandle.getSelectedResult().getName())) {
								baseWorkspace.removeVariableElement(el);
								baseWorkspace.addVariableElement(variableElementMergeHandle.getSelectedResult());
							}
						}
						baseWorkspace.markDirty();
					}
				}

				for (MergeHandle<SoundElement> soundElementMergeHandle : conflictingSoundElements) {
					if (soundElementMergeHandle.getSelectedResultChangeType() == DiffEntry.ChangeType.ADD) {
						baseWorkspace.addSoundElement(soundElementMergeHandle.getSelectedResult());
					} else if (soundElementMergeHandle.getSelectedResultChangeType() == DiffEntry.ChangeType.DELETE) {
						baseWorkspace.removeSoundElement(soundElementMergeHandle.getSelectedResult());
					} else if (soundElementMergeHandle.getSelectedResultChangeType() == DiffEntry.ChangeType.MODIFY) {
						for (SoundElement el : baseWorkspace.getSoundElements()) {
							if (el.getName().equals(soundElementMergeHandle.getSelectedResult().getName())) {
								baseWorkspace.removeSoundElement(el);
								baseWorkspace.addSoundElement(soundElementMergeHandle.getSelectedResult());
							}
						}
						baseWorkspace.markDirty();
					}
				}

				for (MergeHandle<String> langMapMergeHandle : conflictingLangMaps) {
					if (langMapMergeHandle.getSelectedResultChangeType() == DiffEntry.ChangeType.ADD) {
						if (langMapMergeHandle.getResultSide() == ResultSide.LOCAL) {
							baseWorkspace.addLanguage(langMapMergeHandle.getSelectedResult(),
									localWorkspace.getLanguageMap().get(langMapMergeHandle.getSelectedResult()));
						} else if (langMapMergeHandle.getResultSide() == ResultSide.REMOTE) {
							baseWorkspace.addLanguage(langMapMergeHandle.getSelectedResult(),
									remoteWorkspace.getLanguageMap().get(langMapMergeHandle.getSelectedResult()));
						}
					} else if (langMapMergeHandle.getSelectedResultChangeType() == DiffEntry.ChangeType.DELETE) {
						baseWorkspace.removeLocalizationLanguage(langMapMergeHandle.getSelectedResult());
					} else if (langMapMergeHandle.getSelectedResultChangeType() == DiffEntry.ChangeType.MODIFY) {
						if (langMapMergeHandle.getResultSide() == ResultSide.LOCAL) {
							baseWorkspace.updateLanguage(langMapMergeHandle.getSelectedResult(),
									localWorkspace.getLanguageMap().get(langMapMergeHandle.getSelectedResult()));
						} else if (langMapMergeHandle.getResultSide() == ResultSide.REMOTE) {
							baseWorkspace.updateLanguage(langMapMergeHandle.getSelectedResult(),
									remoteWorkspace.getLanguageMap().get(langMapMergeHandle.getSelectedResult()));
						}
					}
				}
			}
		}

		// if remote workspace was not null, we might have a merge so we set local workspace to after merge state
		if (conflictsInWorkspaceFile && !dryRun) {
			// local workspace is not at the same state as merged base workspace
			TerribleWorkspaceHacks.loadStoredDataFrom(localWorkspace, baseWorkspace);

			// to be sure, we save workspace and load it back from file
			localWorkspace.getFileManager().saveWorkspaceDirectlyAndWait();
			TerribleWorkspaceHacks.reloadFromFS(localWorkspace);
		}

		// process workspace base files
		List<GeneratorTemplate> modBaseTemplates = localWorkspace.getGenerator().getModBaseGeneratorTemplatesList(true);
		for (GeneratorTemplate generatorTemplate : modBaseTemplates) {
			for (FileSyncHandle handle : handles) {
				if (isVCSPathThisFile(localWorkspace, handle.getBasePath(), generatorTemplate.getFile())) {
					unprocessedHandles.remove(handle);
					if (!dryRun)
						generatorTemplate.getFile().delete();
				}
			}
		}
		if (!dryRun)
			localWorkspace.getGenerator().generateBase(); // regenerate mod base for state after merge

		// mark all handles that do not have conflicts as merged at this point
		// as now we only need to process remaining unmerged paths
		for (FileSyncHandle handle : handles)
			if (!handle.isUnmerged())
				unprocessedHandles.remove(handle);

		if (!required_user_action) // if not marked as required_user_action yet, we might do this now
			// if we have unmerged files at this point, we will need user action to merge them
			required_user_action = !unprocessedHandles.isEmpty();

		if (!dryRun && !unprocessedHandles.isEmpty()) {
			List<MergeHandle<FileSyncHandle>> unmergedPaths = unprocessedHandles.stream()
					.map(FileSyncHandle::toPathMergeHandle).collect(Collectors.toList());

			VCSFileMergeDialog.show(mcreator, unmergedPaths);

			for (MergeHandle<FileSyncHandle> unmergedPath : unmergedPaths) {
				FileSyncHandle fileSyncHandle = unmergedPath.getLocal();
				mergeNormalFile(localWorkspace, fileSyncHandle, unmergedPath);
			}
		}

		// At the end of sync/merge, we mark all handles resolved, if it is not a dry run
		if (!dryRun) {
			git.rm().addFilepattern(".").call();
			git.add().addFilepattern(".").call();
		}

		return required_user_action;
	}

	private Workspace getVirtualWorkspace(Workspace original, String workspaceString) throws IOException {
		return new Workspace(null) {{
			Workspace retval = WorkspaceFileManager.gson.fromJson(workspaceString, Workspace.class);
			if (retval == null)
				throw new IOException("Failed to parse workspace string");
			TerribleWorkspaceHacks.loadStoredDataFrom(this, retval);
			this.generator = new Generator(this);
			this.generator.setGradleCache(this.generator.getGradleCache());
			this.fileManager = original.getFileManager();
			this.reloadModElements();
			this.reloadFolderStructure();
		}};
	}

	private boolean isVCSPathThisFile(Workspace workspace, String vcsPath, File file) throws IOException {
		return file.getCanonicalPath().equals(new File(workspace.getWorkspaceFolder(), vcsPath).getCanonicalPath());
	}

	private void mergeNormalFile(Workspace workspace, FileSyncHandle fileSyncHandle, MergeHandle<?> mergeHandle) {
		if (fileSyncHandle.getChangeTypeRelativeTo(mergeHandle.getResultSide()) == DiffEntry.ChangeType.ADD
				|| fileSyncHandle.getChangeTypeRelativeTo(mergeHandle.getResultSide()) == DiffEntry.ChangeType.MODIFY) {
			FileIO.writeBytesToFile(fileSyncHandle.getBytes(mergeHandle.getResultSide()),
					fileSyncHandle.toFileInWorkspace(workspace, mergeHandle.getResultSide()));
		} else if (fileSyncHandle.getChangeTypeRelativeTo(mergeHandle.getResultSide()) == DiffEntry.ChangeType.DELETE) {
			File file = fileSyncHandle.toFileInWorkspace(workspace, mergeHandle.getResultSide());
			if (file.isFile())
				file.delete();
			else if (file.isDirectory())
				FileIO.deleteDir(file);
		}
	}

}
