package org.hibernate.infra.bot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;

import org.hibernate.infra.bot.check.Check;
import org.hibernate.infra.bot.check.CheckRunContext;
import org.hibernate.infra.bot.check.CheckRunOutput;
import org.hibernate.infra.bot.check.CheckRunRule;
import org.hibernate.infra.bot.config.DeploymentConfig;
import org.hibernate.infra.bot.config.RepositoryConfig;
import org.hibernate.infra.bot.event.CheckSuiteRequested;

import org.jboss.logging.Logger;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.event.CheckRun;
import io.quarkiverse.githubapp.event.CheckSuite;
import io.quarkiverse.githubapp.event.PullRequest;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHRepository;

class CheckPullRequestContributionRules {

	private static final Logger LOG = Logger.getLogger( CheckPullRequestContributionRules.class );

	private static final Pattern SPACE_PATTERN = Pattern.compile( "\\s+" );

	private static final String COMMENT_INTRO_PASSED = "Thanks for your pull request!\n\n"
			+ "This pull request appears to follow the contribution rules.";
	private static final String COMMENT_INTRO_FAILED = "Thanks for your pull request!\n\n"
			+ "This pull request does not follow the contribution rules. Could you have a look?\n";
	private static final String COMMENT_FOOTER = "\n\n› This message was automatically generated.";

	@Inject
	DeploymentConfig deploymentConfig;

	void pullRequestChanged(
			@PullRequest.Opened @PullRequest.Reopened @PullRequest.Edited @PullRequest.Synchronize
					GHEventPayload.PullRequest payload,
			@ConfigFile("hibernate-github-bot.yml") RepositoryConfig repositoryConfig) throws IOException {
		checkPullRequestContributionRules( payload.getRepository(), repositoryConfig,
				payload.getPullRequest()
		);
	}

	void checkRunRequested(@CheckRun.Rerequested GHEventPayload.CheckRun payload,
			@ConfigFile("hibernate-github-bot.yml") RepositoryConfig repositoryConfig) throws IOException {
		for ( GHPullRequest pullRequest : payload.getCheckRun().getPullRequests() ) {
			checkPullRequestContributionRules( payload.getRepository(), repositoryConfig, pullRequest );
		}
	}

	void checkSuiteRequested(@CheckSuiteRequested @CheckSuite.Rerequested GHEventPayload.CheckSuite payload,
			@ConfigFile("hibernate-github-bot.yml") RepositoryConfig repositoryConfig) throws IOException {
		for ( GHPullRequest pullRequest : payload.getCheckSuite().getPullRequests() ) {
			checkPullRequestContributionRules( payload.getRepository(), repositoryConfig, pullRequest );
		}
	}

	private void checkPullRequestContributionRules(GHRepository repository, RepositoryConfig repositoryConfig,
			GHPullRequest pullRequest)
			throws IOException {
		if ( !shouldCheck( repository, pullRequest ) ) {
			return;
		}

		CheckRunContext context = new CheckRunContext( deploymentConfig, repository, repositoryConfig, pullRequest );
		List<Check> checks = createChecks( repositoryConfig );
		List<CheckRunOutput> outputs = new ArrayList<>();
		for ( Check check : checks ) {
			outputs.add( Check.run( context, check ) );
		}

		boolean passed = outputs.stream().allMatch( CheckRunOutput::passed );
		GHIssueComment existingComment = findExistingComment( pullRequest );
		// Avoid creating noisy comments for no reason, in particular if checks passed
		// or if the pull request was already closed.
		if ( existingComment == null && ( passed || GHIssueState.CLOSED.equals( pullRequest.getState() ) ) ) {
			return;
		}

		StringBuilder message = new StringBuilder( passed ? COMMENT_INTRO_PASSED : COMMENT_INTRO_FAILED );
		outputs.forEach( output -> output.appendFailingRules( message ) );
		message.append( COMMENT_FOOTER );

		if ( !deploymentConfig.isDryRun() ) {
			if ( existingComment == null ) {
				pullRequest.comment( message.toString() );
			}
			else {
				existingComment.update( message.toString() );
			}
		}
		else {
			LOG.info( "Pull request #" + pullRequest.getNumber() + " - Add comment " + message.toString() );
		}
	}

	// GitHub sometimes mentions pull requests in the payload that are definitely not related to the changes,
	// such as very old pull requests on the branch that just got updated,
	// or pull requests on different repositories.
	// We have to ignore those, otherwise we'll end up creating comments on old pull requests.
	private boolean shouldCheck(GHRepository repository, GHPullRequest pullRequest) {
		return !GHIssueState.CLOSED.equals( pullRequest.getState() )
				&& repository.getId() == pullRequest.getBase().getRepository().getId();
	}

	private GHIssueComment findExistingComment(GHPullRequest pullRequest) throws IOException {
		for ( GHIssueComment comment : pullRequest.listComments() ) {
			if ( comment.getBody().startsWith( COMMENT_INTRO_PASSED )
					|| comment.getBody().startsWith( COMMENT_INTRO_FAILED ) ) {
				return comment;
			}
		}
		return null;
	}

	private List<Check> createChecks(RepositoryConfig repositoryConfig) {
		List<Check> checks = new ArrayList<>();
		checks.add( new TitleCheck() );

		if ( repositoryConfig != null && repositoryConfig.jira != null ) {
			repositoryConfig.jira.getIssueKeyPattern()
					.ifPresent( issueKeyPattern -> checks.add( new JiraIssuesCheck( issueKeyPattern ) ) );
		}

		return checks;
	}

	static class TitleCheck extends Check {

		TitleCheck() {
			super( "Contribution — Title" );
		}

		@Override
		public void perform(CheckRunContext context, CheckRunOutput output) {
			String title = context.pullRequest.getTitle();

			output.rule( "The pull request title should contain at least 2 words to describe the change properly" )
					.result( title != null && SPACE_PATTERN.split( title.trim() ).length >= 2 );
			output.rule( "The pull request title should not end with a dot" )
					.result( title == null || !title.endsWith( "." ) );
			output.rule( "The pull request title should not end with an ellipsis (make sure the title is complete)" )
					.result( title == null || !title.endsWith( "…" ) );
		}
	}

	static class JiraIssuesCheck extends Check {

		private final Pattern issueKeyPattern;

		JiraIssuesCheck(Pattern issueKeyPattern) {
			super( "Contribution — JIRA issues" );
			this.issueKeyPattern = issueKeyPattern;
		}

		@Override
		public void perform(CheckRunContext context, CheckRunOutput output) throws IOException {
			String title = context.pullRequest.getTitle();
			String body = context.pullRequest.getBody();

			Set<String> issueKeys = new LinkedHashSet<>();
			Set<String> commitsWithMessageNotStartingWithIssueKey = new LinkedHashSet<>();
			for ( GHPullRequestCommitDetail commitDetails : context.pullRequest.listCommits() ) {
				GHPullRequestCommitDetail.Commit commit = commitDetails.getCommit();
				String message = commit.getMessage();
				Matcher commitMessageIssueKeyMatcher = issueKeyPattern.matcher( message );
				int issueKeyIndex = commitMessageIssueKeyMatcher.find() ? commitMessageIssueKeyMatcher.start() : -1;
				if ( issueKeyIndex == 0 ) {
					issueKeys.add( commitMessageIssueKeyMatcher.group() );
				}
				else {
					commitsWithMessageNotStartingWithIssueKey.add( commitDetails.getSha() );
				}
			}

			CheckRunRule commitRule =
					output.rule( "All commit messages should start with a JIRA issue key matching pattern `"
							+ issueKeyPattern + "`" );
			if ( commitsWithMessageNotStartingWithIssueKey.isEmpty() ) {
				commitRule.passed();
			}
			else {
				commitRule.failed( "Offending commits: " + commitsWithMessageNotStartingWithIssueKey );
			}

			CheckRunRule pullRequestRule = output.rule(
					"All JIRA issues addressed by commits should be mentioned in the PR title or body" );
			List<String> issueKeysNotMentionedInPullRequest = issueKeys.stream()
					.filter( issueKey -> !title.contains( issueKey ) && !body.contains( issueKey ) )
					.collect( Collectors.toList() );
			if ( issueKeysNotMentionedInPullRequest.isEmpty() ) {
				pullRequestRule.passed();
			}
			else {
				pullRequestRule.failed( "Issues not mentioned: " + issueKeysNotMentionedInPullRequest );
			}
		}
	}

}
