/*
 * Copyright 2024 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.jgit.ignore.FastIgnoreRule;
import org.openrewrite.jgit.ignore.IgnoreNode;
import org.openrewrite.text.PlainText;
import org.openrewrite.text.PlainTextVisitor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

import static java.util.Comparator.comparingInt;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.join;
import static org.openrewrite.ExcludeFileFromGitignore.Repository;
import static org.openrewrite.jgit.ignore.IgnoreNode.MatchResult.*;

@Value
@EqualsAndHashCode(callSuper = false)
public class ExcludeFileFromGitignore extends ScanningRecipe<Repository> {

    @Option(displayName = "Paths", description = "The paths to find and remove from the gitignore files.", example = "/folder/file.txt")
    List<String> paths;

    @Override
    public String getDisplayName() {
        return "Remove ignoral of files or directories from .gitignore";
    }

    @Override
    public String getDescription() {
        return "This recipe will remove a file or directory from the .gitignore file. " +
               "If the file or directory is already in the .gitignore file, it will be removed or negated. " +
               "If the file or directory is not in the .gitignore file, no action will be taken.";
    }

    @Override
    public Repository getInitialValue(ExecutionContext ctx) {
        return new Repository();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Repository acc) {
        return Preconditions.check(new FindSourceFiles("**/.gitignore"), new PlainTextVisitor<ExecutionContext>() {
            @Override
            public PlainText visitText(PlainText text, ExecutionContext ctx) {
                try {
                    acc.addGitignoreFile(text);
                } catch (IOException e) {
                    throw new RecipeException("Failed to parse the .gitignore file", e);
                }
                return super.visitText(text, ctx);
            }
        });
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Repository acc) {

        for (String path : paths) {
            acc.exclude(path);
        }

        return Preconditions.check(new FindSourceFiles("**/.gitignore"), new PlainTextVisitor<ExecutionContext>() {
            @Override
            public PlainText visitText(PlainText text, ExecutionContext ctx) {
                String gitignoreFileName = text.getSourcePath().toString();
                gitignoreFileName = gitignoreFileName.startsWith("/") ? gitignoreFileName : "/" + gitignoreFileName;
                IgnoreNode ignoreNode = acc.rules.get(gitignoreFileName.substring(0, gitignoreFileName.lastIndexOf("/") + 1));
                if (ignoreNode != null) {
                    String separator = text.getText().contains("\r\n") ? "\r\n" : "\n";
                    List<String> newRules = ignoreNode.getRules().stream().map(FastIgnoreRule::toString).collect(toList());
                    String[] currentContent = text.getText().split(separator);
                    return text.withText(join(sortRules(currentContent, newRules), separator));
                }
                return super.visitText(text, ctx);
            }

            private List<String> sortRules(String[] originalRules, List<String> newRules) {
                LinkedList<String> results = new LinkedList<>();
                Arrays.stream(originalRules).filter(line -> {
                    if (line.startsWith("#") || StringUtils.isBlank(line)) {
                        return true;
                    }
                    return newRules.stream().anyMatch(line::equalsIgnoreCase);
                }).forEach(results::add);

                int resultsIndexCurrentlyAt = 0;
                for (String newRule : newRules) {
                    List<String> resultsSubList = results.subList(resultsIndexCurrentlyAt, results.size());
                    if (resultsSubList.stream().noneMatch(rule -> rule.equalsIgnoreCase(newRule))) {
                        if (resultsIndexCurrentlyAt >= results.size()) {
                            results.add(newRule);
                        } else {
                            results.add(resultsIndexCurrentlyAt, newRule);
                        }
                    } else {
                        resultsIndexCurrentlyAt += resultsSubList.indexOf(newRule);
                    }
                    resultsIndexCurrentlyAt++;
                }
                return results;
            }
        });
    }

    public static class Repository {
        private final Map<String, IgnoreNode> rules = new HashMap<>();

        public void exclude(String path) {
            String normalizedPath = path.startsWith("/") ? path : "/" + path;
            List<String> impactingFiles = rules.keySet()
                    .stream()
                    .filter(k -> normalizedPath.toLowerCase().startsWith(k.toLowerCase()))
                    .sorted(comparingInt(String::length).reversed())
                    .collect(toList());

            IgnoreNode.MatchResult isIgnored;
            for (String impactingFile : impactingFiles) {
                IgnoreNode ignoreNode = rules.get(impactingFile);
                String nestedPath = normalizedPath.substring(impactingFile.length() - 1);
                isIgnored = isIgnored(ignoreNode, nestedPath);
                if (CHECK_PARENT == isIgnored) {
                    continue;
                }
                if (IGNORED == isIgnored) {
                    List<FastIgnoreRule> remainingRules = new ArrayList<>();
                    boolean done = false;
                    for (FastIgnoreRule rule : ignoreNode.getRules()) {
                        if (!rule.getResult() || !isMatch(rule, nestedPath)) {
                            // If this rule has nothing to do with the path to remove / it is a negated rule, we keep it.
                            remainingRules.add(rule);
                            continue;
                        } else if (rule.toString().equals(nestedPath)) {
                            // If this rule is an exact match to the path to remove, we remove it.
                            continue;
                        } else if (isMatch(rule, nestedPath)) {
                            // If this rule is a directory match, we need to negate the rule for the given path.
                            remainingRules.add(rule);
                            // If it's already negated by another rule encountered before, we do not need to add another negation.
                            if (!done) {
                                remainingRules.add(new FastIgnoreRule("!" + nestedPath));
                                done = true;
                            }
                            continue;
                        }
                        // If we still have the rule, we keep it. --> not making changes to an unknown flow.
                        remainingRules.add(rule);
                    }
                    IgnoreNode replacedNode = new IgnoreNode(remainingRules);
                    rules.put(impactingFile, replacedNode);
                    if (CHECK_PARENT == isIgnored(replacedNode, nestedPath)) {
                        continue;
                    }
                }
                // There is already an ignore rule for the path, so not needed to check parent rules.
                break;
            }
        }

        public void addGitignoreFile(PlainText text) throws IOException {
            String gitignoreFileName = text.getSourcePath().toString();
            gitignoreFileName = gitignoreFileName.startsWith("/") ? gitignoreFileName : "/" + gitignoreFileName;
            IgnoreNode ignoreNode = new IgnoreNode();
            ignoreNode.parse(gitignoreFileName, new ByteArrayInputStream(text.getText().getBytes()));
            rules.put(gitignoreFileName.substring(0, gitignoreFileName.lastIndexOf("/") + 1), ignoreNode);
        }

        // We do not use jgit's IgnoreNode#isIgnored method because it does not handle the directory correct always.
        // See the difference between rule.isMatch in the pathMatch parameter.
        private boolean isMatch(FastIgnoreRule rule, String path) {
            String rulePath = rule.toString();
            if (rulePath.startsWith("!")) {
                rulePath = rulePath.substring(1);
            }
            if (rule.dirOnly() && path.contains(rulePath)) {
                return rule.isMatch(path, true, false);
            }
            return rule.isMatch(path, true, true);
        }

        private IgnoreNode.MatchResult isIgnored(IgnoreNode ignoreNode, String path) {
            IgnoreNode.MatchResult isIgnored = CHECK_PARENT;
            for (int i = ignoreNode.getRules().size() - 1; i > -1; i--) {
                FastIgnoreRule rule = ignoreNode.getRules().get(i);
                if (isMatch(rule, path)) {
                    if (rule.getResult()) {
                        isIgnored = IGNORED;
                    } else {
                        return NOT_IGNORED;
                    }
                }
            }
            return isIgnored;
        }
    }
}
